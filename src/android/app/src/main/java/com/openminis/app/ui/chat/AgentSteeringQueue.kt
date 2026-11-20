package com.openminis.app.ui.chat

import com.openminis.app.service.ActiveRun
import com.openminis.app.service.ActiveRunContext

/**
 * [T-agent-steering] Turn-boundary steering queue, ported from the pi agent
 * (`packages/agent/src/types.ts` QueueMode, `agent.ts` PendingMessageQueue).
 *
 * The point: while an agent loop is running, the user's next message should
 * reach the model WITHOUT killing the run. A queued message is held here and
 * committed as a real user turn at the next turn boundary — after the current
 * tool round completes, before the next request is built.
 *
 * Why not reuse `_promptQueue`: that queue is drained only AFTER the loop
 * ends (the whole point of a queued send is to run later, as a separate
 * turn). Steering is the opposite — it must land mid-run. Sharing one StateFlow
 * would make "queued" ambiguous at every read site, and the drain-after-loop
 * path would race the boundary drain. Two queues, two lifecycles.
 *
 * Ordering: one lock over an ArrayDeque, so offer/drain/restore are ordered
 * and atomic without a StateFlow. The queue has no intrinsic UI; [steerPrompt]
 * materialises a placeholder bubble per offer so the user sees their words.
 */
internal enum class QueueMode {
    /** Drain and inject every queued message at the next boundary. */
    ALL,

    /** Inject only the oldest message per boundary, leaving the rest queued. */
    ONE_AT_A_TIME,
}

/**
 * One queued steering message. The id threads through the queue so the offer
 * can materialise a placeholder bubble immediately and the boundary commit can
 * replace it in place. The placeholder/sysinfo ids are DERIVED from the entry
 * id, so no extra state has to be carried or persisted.
 */
internal data class SteeringEntry(
    val id: String,
    val text: String,
) {
    /** Placeholder-bubble id: the offer appends it, the commit finds it. */
    val placeholderMessageId: String get() = "steer_q_$id"

    /** System-info "queued" row id: the offer appends it, the commit drops it. */
    val sysinfoMessageId: String get() = "steer_sys_$id"
}

internal class AgentSteeringQueue(startMode: QueueMode = QueueMode.ONE_AT_A_TIME) {

    private val lock = Any()
    private val items = ArrayDeque<SteeringEntry>()

    /** Agent steeringMode: switchable while the run is live. */
    @Volatile
    var mode: QueueMode = startMode

    /** Enqueue one steering message. Blank text is rejected (an empty user msg is a 400). */
    fun offer(text: String): SteeringEntry? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val entry = SteeringEntry(
            id = "steer_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
            text = trimmed,
        )
        synchronized(lock) {
            items.addLast(entry)
        }
        return entry
    }

    /** Pop the oldest single message (one-at-a-time drain). Null when empty. */
    fun drainOne(): SteeringEntry? = synchronized(lock) {
        if (items.isEmpty()) null else items.removeFirst()
    }

    /** Pop everything (all-mode drain). Empty list when empty. */
    fun drainAll(): List<SteeringEntry> = synchronized(lock) {
        val out = items.toList()
        items.clear()
        out
    }

    /** Drain respecting the current mode: ALL drains everything, ONE_AT_A_TIME drains the oldest. */
    fun drainPerMode(ownsQueue: () -> Boolean = { true }): List<SteeringEntry> = synchronized(lock) {
        if (!ownsQueue() || items.isEmpty()) return@synchronized emptyList()
        when (mode) {
            QueueMode.ALL -> items.toList().also { items.clear() }
            QueueMode.ONE_AT_A_TIME -> listOf(items.removeFirst())
        }
    }

    /** Return a batch only if it did not commit; newer offers stay behind it. */
    fun restoreUnsent(entries: List<SteeringEntry>) = synchronized(lock) {
        val present = items.mapTo(hashSetOf()) { it.id }
        for (entry in entries.asReversed()) if (present.add(entry.id)) items.addFirst(entry)
    }

    /**
     * [T-agent-steering-exit-fallback] Mode-independent fallback flush: hand
     * EVERY queued entry to [sink] and clear the queue. True when at least one
     * entry moved. Unlike [drainPerMode] this ignores the mode — a fallback
     * flush must not strand entries behind a ONE_AT_A_TIME boundary the ending
     * run will never reach. The sink indirection keeps the routing policy out
     * of the pure queue, so the fallback stays unit-testable with a fake sink.
     */
    fun drainToFallback(sink: (SteeringEntry) -> Unit): Boolean {
        val entries = drainAll()
        entries.forEach(sink)
        return entries.isNotEmpty()
    }

    /** Withdraw a queued entry (queued-bubble withdraw path). True when removed. */
    fun remove(entryId: String): Boolean = synchronized(lock) {
        val before = items.size
        items.removeAll { it.id == entryId }
        items.size != before
    }

    /** Rewrite a queued entry's text (queued-bubble edit path). Blank rejected. */
    fun updateText(entryId: String, newText: String): Boolean = synchronized(lock) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return false
        val idx = items.indexOfFirst { it.id == entryId }
        if (idx < 0) return false
        items[idx] = items[idx].copy(text = trimmed)
        true
    }

    /** Snapshot of the queued texts, oldest first — for tests and diagnostics. */
    fun snapshot(): List<SteeringEntry> = synchronized(lock) { items.toList() }

    val isEmpty: Boolean get() = synchronized(lock) { items.isEmpty() }

    val size: Int get() = synchronized(lock) { items.size }

    fun clear() = synchronized(lock) { items.clear() }
}

/**
 * Per-VM steering state, stamped with the session it was offered under.
 * [sessionId] is informational: the re-stamp rule below means it no longer
 * gates a clear, but it stays so debugging can see which session a live queue
 * belongs to.
 */
internal class SteerState(val queue: AgentSteeringQueue) {
    var sessionId: String = ""
}

/**
 * The per-VM store behind [steeringState]. Keyed by Any (the VM in production,
 * a plain token in tests) so the re-stamp rule is JVM-testable without a
 * ChatViewModel. [vmSteerState] is the process-wide singleton; a new
 * conversation never shares an owner, so cross-session isolation is structural
 * (the WeakHashMap entry dies with the VM).
 *
 * Thread model: [stateFor] is reachable from Main (composer offers) AND from
 * headless entry points on background dispatchers, so get-or-create is one
 * critical section under `synchronized(states)` — a hand-rolled get +
 * put-if-null, NOT [MutableMap.getOrPut], whose compound get-then-put is only
 * atomic when every reader provably holds the same monitor.
 */
internal class SteeringSessionStore {
    private val states =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, SteerState>())

    internal fun stateFor(owner: Any, sessionId: String): SteerState = synchronized(states) {
        var state = states[owner]
        if (state == null) {
            state = SteerState(AgentSteeringQueue())
            states[owner] = state
        }
        state.sessionId = sessionId
        state
    }
}

private val vmSteerState = SteeringSessionStore()

/**
 * This VM's queue, re-stamped with the session id on every access.
 *
 * [T-agent-steering-stamp-keep] Re-stamp, NEVER clear. The old rule
 * (`sessionId != sid -> queue.clear()`) assumed a stamp change meant a
 * different conversation, but a VM is per-session (the store aliases the
 * draft→persisted promotion instead of re-keying), so the one stamp
 * transition in its life happens WHILE a run is streaming: `_isStreaming` is
 * claimed synchronously in sendMessage before the draft is promoted, so an
 * offer accepted in that window was stamped with the draft id and the very
 * next access wiped it — the user's words destroyed between composer and
 * request build. Keeping the content is safe: cross-session isolation is
 * structural.
 */
internal fun ChatViewModel.steeringState(): SteerState =
    vmSteerState.stateFor(this, activeSessionId)

/**
 * Public steering entry point (pi agent.ts steer()). Accepted ONLY while a run
 * is streaming: false means "nothing is running" and the caller should fall
 * back to a normal send — steering that would sit forever in an idle queue is
 * worse than a plain send.
 */
internal fun ChatViewModel.steerPrompt(text: String): Boolean {
    if (!_isStreaming.value) return false
    val entry = steeringState().queue.offer(text) ?: return false
    // The offer materialises TWO UI forms immediately: the ephemeral
    // system-info "queued" row (the hint) and a placeholder USER bubble
    // carrying the actual words. Both ids are DERIVED from the entry id, so
    // the boundary commit can find and replace them in place — appendSystemInfo
    // generates its id from the clock and would be unlocatable afterwards.
    _messages.value = trimLoadedWindow(
        _messages.value +
            ChatMessage(
                id = entry.sysinfoMessageId,
                role = "system",
                content = "",
                toolBlocks = listOf(
                    AssistantBlock(
                        id = "${entry.sysinfoMessageId}_blk",
                        kind = "info",
                        content = context.getString(com.openminis.app.R.string.vm_steering_queued),
                        toolName = "info",
                    ),
                ),
            ) +
            ChatMessage(
                id = entry.placeholderMessageId,
                role = "user",
                content = entry.text,
                isQueued = true,
                queuedPromptId = entry.id,
            ),
    )
    return true
}

/**
 * Keep-alive (pi agent-loop.ts runLoop): true while steering text is waiting
 * for a boundary inside the live run. runAgentLoop's natural-exit site (the
 * turn that produced no tool calls) continues the loop on true, so an offer
 * accepted during that FINAL turn — which has no later boundary — still gets
 * its in-run turn instead of stranding until some future run.
 */
internal fun ChatViewModel.steeringKeepAlive(): Boolean =
    _isStreaming.value && !steeringState().queue.isEmpty

/**
 * Turn ownership gate, shared by the steering drain and the queued-prompt
 * drain. A drain may only commit while the VM still points at [sid] and the
 * owning run has not been stopped — otherwise the commit would land in a
 * conversation the user has already left, or resurrect a cancelled turn.
 */
internal fun ChatViewModel.ownsQueuedTurn(sid: String, run: ActiveRun?): Boolean =
    activeSessionId == sid && run?.isStopped != true

/**
 * Resolve which session a queued commit belongs to, or null when this VM no
 * longer owns it. The only permitted owner change is this VM's own
 * draft→persisted promotion: the queue was stamped with the draft id, and by
 * commit time `ensureSession()` has promoted it to a real id.
 */
internal suspend fun ChatViewModel.queuedTurnSession(
    expectedSessionId: String?,
    run: ActiveRun?,
): String? {
    val origin = expectedSessionId ?: activeSessionId
    if (origin != activeSessionId || run?.isStopped == true) return null
    if (run != null && origin !in run.ownerSessionIds) return null
    if (realSessionId.isNotBlank()) return origin
    // The only permitted owner change is this VM's own draft promotion.
    val promoted = ensureSession()
    return promoted.takeIf { ownsQueuedTurn(it, run) }
}

/** Current run, for callers that need the ownership pair. */
internal fun currentRun(): ActiveRun? = ActiveRunContext.current()
