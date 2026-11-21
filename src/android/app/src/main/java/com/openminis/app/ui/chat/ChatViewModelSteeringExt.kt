package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * [T-agent-steering] Turn-boundary commit for the steering queue.
 *
 * Called from the agent loop at the post-tool-result boundary — the only point
 * where the previous request's tool round is fully answered and the next
 * request has not been built yet. Injecting here means the model sees the
 * user's steering text as a normal user turn in the SAME run, so the run keeps
 * its accumulated plan instead of restarting.
 *
 * Two things happen per entry, and the order matters:
 *  1. the DB row is appended (durable — a crash after this point still leaves
 *     the user's words in the transcript);
 *  2. the placeholder bubble is replaced in place and the "queued" sysinfo row
 *     is dropped, so the transcript does not briefly show the message twice.
 *
 * Returns the committed user message ids, oldest first. An empty list means
 * nothing was committed (queue empty, or this VM no longer owns the turn).
 */
internal suspend fun ChatViewModel.drainSteeringIntoRun(
    expectedSessionId: String? = null,
): List<String> {
    val sid = expectedSessionId ?: activeSessionId
    if (!ownsQueuedTurn(sid, currentRun())) return emptyList()
    val queue = steeringState().queue
    val entries = queue.drainPerMode { ownsQueuedTurn(sid, currentRun()) }
    if (entries.isEmpty()) return emptyList()

    val userIds = mutableListOf<String>()
    var committed = false
    try {
        for (entry in entries) {
            val partsJson = buildUserPartsJson(entry.text, emptyList(), null)
            val persisted = runCatching {
                chatRepository.appendMessage(sid, "user", partsJson)
            }.getOrNull()
            if (persisted == null) {
                // Persistence threw for this entry — keep it (and everything
                // after it) queued so the user's words are not silently lost
                // and ordering stays intact.
                queue.restoreUnsent(entries.drop(userIds.size))
                return userIds
            }
            userIds += persisted.id
            appendBoundedHistory(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = entry.text,
                    contentParts = listOf(AgentContentPart.Text(entry.text)),
                    dbMessageId = persisted.id,
                ),
            )
            // Replace the placeholder bubble (same id the offer created) with
            // the persisted row, and drop the ephemeral queued hint.
            withContext(Dispatchers.Main.immediate) {
                _messages.value = _messages.value
                    .map { msg ->
                        if (msg.id == entry.placeholderMessageId) {
                            msg.copy(
                                id = persisted.id,
                                isQueued = false,
                                queuedPromptId = null,
                                sourceDbIds = listOf(persisted.id),
                            )
                        } else {
                            msg
                        }
                    }
                    .filterNot { it.id == entry.sysinfoMessageId }
                notePersistedUiRow(entry.placeholderMessageId, persisted.id)
            }
        }
        committed = true
    } finally {
        if (!committed) queue.restoreUnsent(entries.drop(userIds.size))
    }
    return userIds
}

/**
 * [T-agent-steering] Exit flush: the run is ending with entries still queued.
 *
 * No boundary is left in this run, so the text is handed to the QUEUED-PROMPT
 * path instead of being committed inline.
 *
 * Why not persist inline: the row would land with nothing ever streaming into
 * an answer — the user's message sits there unanswered, which reads as a hung
 * assistant. Why not call [drainQueueIntoStandaloneTurn] either: that helper
 * only BUILDS a turn (DB row + a fresh awaiting bubble) and relies on its
 * caller looping again; outside the loop nothing would ever stream into that
 * bubble. [drainQueuedPrompts] is the single place that both persists and
 * re-enters the loop, and every run entry point (send / resume / retry /
 * rerun) calls it immediately after the loop returns — so the flush only has
 * to make the text visible to it.
 *
 * Shape matters: [drainQueuedPrompts] locates the bubble to retire by
 * `isQueued && content == userText`, where userText is ALL drained prompts
 * joined by a blank line. A one-prompt-per-bubble flush would therefore leave
 * every bubble but the merged text unmatched — stuck "queued" forever. So the
 * entries are merged into ONE prompt whose text equals the merged bubble
 * content, exactly the shape that path already produces for a multi-prompt
 * queue.
 *
 * If that drain is skipped (the run was stopped), the prompt stays in
 * `_promptQueue` with its queued bubble intact and rides along with the next
 * send — the user's words survive either way.
 */
internal suspend fun ChatViewModel.flushSteeringOnExit(): Boolean {
    val queue = steeringState().queue
    if (queue.isEmpty) return false
    val entries = queue.drainAll()
    if (entries.isEmpty()) return false

    val merged = entries.joinToString("\n\n") { it.text.trim() }.trim()
    if (merged.isEmpty()) {
        // Nothing but blank text — put them back rather than silently dropping.
        queue.restoreUnsent(entries)
        return false
    }

    val head = entries.first()
    val retiredBubbleIds = entries.drop(1).mapTo(hashSetOf()) { it.placeholderMessageId }
    val sysinfoIds = entries.mapTo(hashSetOf()) { it.sysinfoMessageId }
    val promptId = steeringFallbackPromptId(head.id)

    // NonCancellable: a cancelled run still delivers.
    return withContext(NonCancellable) {
        withContext(Dispatchers.Main.immediate) {
            _messages.value = _messages.value
                .filterNot { it.id in sysinfoIds || it.id in retiredBubbleIds }
                .map { msg ->
                    if (msg.id == head.placeholderMessageId) {
                        msg.copy(content = merged, queuedPromptId = promptId)
                    } else {
                        msg
                    }
                }
            _promptQueue.update { q -> q + QueuedPrompt(promptId, merged) }
        }
        AppLogger.info(
            ChatViewModel.TAG_STREAM,
            "steering exit flush: handed ${entries.size} queued turn(s) to the queued-prompt path",
        )
        true
    }
}

/** Id shared by a fallback-flushed prompt and the bubble that renders it. */
private fun steeringFallbackPromptId(entryId: String): String = "steer_fallback_$entryId"

// ---------------------------------------------------------------------------
// [T-agent-steering] Thin loop-facing wrappers.
//
// The agent loop lives in a 2000-line file under a hard line ceiling, so each
// call site there stays ONE line and the logging / failure containment lives
// here. runCatching is deliberate at every entry: a steering failure must never
// abort a run that is otherwise healthy.
// ---------------------------------------------------------------------------

/** Boundary commit; returns how many turns landed. Never throws. */
internal suspend fun ChatViewModel.tryDrainSteeringAtBoundary(): Int =
    runCatching { drainSteeringIntoRun(expectedSessionId = activeSessionId).size }
        .getOrElse {
            AppLogger.warning(ChatViewModel.TAG_STREAM, "Steering boundary commit failed: ${it.message}")
            0
        }

/**
 * Keep-alive probe for the no-tool-call exit path: commits queued text and
 * reports whether the run should loop again instead of breaking.
 */
internal suspend fun ChatViewModel.trySteeringKeepAlive(turn: Int): Boolean {
    if (!steeringKeepAlive()) return false
    val committed = tryDrainSteeringAtBoundary()
    if (committed > 0) {
        AppLogger.info(
            ChatViewModel.TAG_STREAM,
            "runAgentLoop turn=$turn steering keep-alive: committed $committed queued turn(s)",
        )
        return true
    }
    return false
}

/** Exit flush; never throws. */
internal suspend fun ChatViewModel.tryFlushSteeringOnExit(): Boolean =
    runCatching { flushSteeringOnExit() }
        .getOrElse {
            AppLogger.warning(ChatViewModel.TAG_STREAM, "Steering exit flush failed: ${it.message}")
            false
        }
