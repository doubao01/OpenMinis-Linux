package com.openminis.app.ui.chat

import com.openminis.app.R
import com.openminis.app.agent.ContextAssemblySnapshot
import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.model.ContextUsage
import com.openminis.app.provider.api.ChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// [T-context-breakdown] Per-block composition of the NEXT request, for the
// Token Usage sheet. Ported from the upstream branch with one structural
// difference: upstream splits the system prompt into semantic sections at
// assembly time (`assembleSystemPrompt` returns labelled fragments). This
// tree assembles a single `buildSystemPrompt()` string and already records a
// per-`#`-heading size breakdown of it in [ContextAssemblySnapshot], so the
// section rows come from that snapshot instead of a second splitter. The
// history / tools / attachment rows are measured directly off the projected
// request, which is exact for everything the snapshot cannot see.

/** Semantic blocks of the next request payload, in the order the UI shows them. */
internal enum class ContextBlockKey {
    /** Identity, tool catalogue and guidance — the stable head of the system prompt. */
    SYSTEM_TOOLS,

    /** Skill + MCP disclosure fragments injected after the base prompt. */
    SKILLS_MCPS,

    /** Memory injection (project rules, GLOBAL.md levels, daily window + index). */
    MEMORY,

    /** Per-turn dynamic tail: WorldBook, learned prefs, task anchor, runtime context. */
    RUNTIME_TAIL,

    /** Goal context + live plan-board / work-report / recall injections. */
    GOAL_PLAN,

    /** The effective (compacted / trimmed) agent history window. */
    HISTORY,

    /** Advertised tool schemas — charged once per request by every provider. */
    TOOL_SCHEMAS,

    /** Composer attachments not yet sent (images count like in-history images). */
    PENDING_ATTACHMENTS;

    /**
     * Localized row label. Lives on the enum so a new key cannot be added
     * without the compiler demanding a string resource for it.
     */
    @androidx.annotation.StringRes
    fun labelRes(): Int = when (this) {
        SYSTEM_TOOLS -> R.string.token_usage_block_system_tools
        SKILLS_MCPS -> R.string.token_usage_block_skills_mcps
        MEMORY -> R.string.token_usage_block_memory
        RUNTIME_TAIL -> R.string.token_usage_block_runtime_tail
        GOAL_PLAN -> R.string.token_usage_block_goal_plan
        HISTORY -> R.string.token_usage_block_history
        TOOL_SCHEMAS -> R.string.token_usage_block_tool_schemas
        PENDING_ATTACHMENTS -> R.string.token_usage_block_pending
    }
}

/** One block of the breakdown. [tokens] is a rough estimate, [share] is vs the sum. */
internal data class ContextBlock(
    val key: ContextBlockKey,
    val tokens: Int,
    /** 0f..1f share of [ContextBreakdown.totalTokens]; 0f when the total is 0. */
    val share: Float,
)

/** Block estimates describe the composition; the input total and allowance describe the request. */
internal data class ContextBreakdown(
    val blocks: List<ContextBlock>,
    val totalTokens: Int,
    val usedTokens: Int,
    val windowTokens: Int,
    val maxOutputTokens: Int,
    val measuredPrefixTokens: Int? = null,
) {
    val contextUsage: ContextUsage
        get() = ContextUsage(usedTokens.toLong(), windowTokens.toLong())
}

/**
 * Pure core of the breakdown. Takes pre-computed numbers so the JVM test can
 * pin the arithmetic without a ViewModel or Android types.
 */
internal object ContextBreakdownMath {

    /**
     * Block estimates are intentionally rough; the panel labels them ±10%.
     * The test uses the same constant to prove the block sum stays inside
     * the tolerance of an independently measured whole.
     */
    const val ESTIMATE_TOLERANCE = 0.10f

    fun sectionTokens(text: String): Int = ContextTokenMeter.rawTextTokens(text)

    /**
     * Blank/zero contributors do not produce rows. Pending attachments are
     * their own row (not folded into HISTORY) because they are the one block
     * the user can still delete before sending.
     */
    fun build(
        sections: List<Pair<ContextBlockKey, Int>>,
        historyTokens: Int,
        pendingAttachmentTokens: Int,
        usedTokens: Int,
        windowTokens: Int,
        maxOutputTokens: Int,
        toolSchemaTokens: Int,
        measuredPrefixTokens: Int? = null,
    ): ContextBreakdown {
        val rows = ArrayList<ContextBlock>(sections.size + 3)
        for ((key, tokens) in sections) {
            if (tokens > 0) rows += ContextBlock(key, tokens, 0f)
        }
        if (toolSchemaTokens > 0) rows += ContextBlock(ContextBlockKey.TOOL_SCHEMAS, toolSchemaTokens, 0f)
        if (historyTokens > 0) rows += ContextBlock(ContextBlockKey.HISTORY, historyTokens, 0f)
        if (pendingAttachmentTokens > 0) {
            rows += ContextBlock(ContextBlockKey.PENDING_ATTACHMENTS, pendingAttachmentTokens, 0f)
        }
        val sum = rows.sumOf { it.tokens }
        val share = if (sum <= 0) 0f else 1f / sum.toFloat()
        return ContextBreakdown(
            blocks = rows.map { it.copy(share = it.tokens * share) },
            totalTokens = sum,
            usedTokens = usedTokens,
            windowTokens = windowTokens,
            maxOutputTokens = maxOutputTokens,
            measuredPrefixTokens = measuredPrefixTokens,
        )
    }
}

/** A measured prefix from the provider, or the local estimate when none exists yet. */
internal data class ContextTokenEstimate(
    val tokens: Int,
    val measuredPrefixTokens: Int? = null,
)

/**
 * Build the breakdown for the request that WOULD be sent right now.
 *
 * Cost control: this walks the whole effective history and the system prompt.
 * The sheet calls it on open only (never per keystroke), and the walk runs on
 * [Dispatchers.Default].
 */
internal suspend fun ChatViewModel.buildContextBreakdown(): ContextBreakdown =
    withContext(Dispatchers.Default) {
        val request = projectedRequestForBreakdown()
        val systemPrompt = request.systemPrompt.orEmpty()
        val sections = systemPromptSectionTokensForBreakdown(systemPrompt)
        val historyTokens = ContextTokenMeter.rawMessagesTokens(request.messages)
        val toolTokens = ContextTokenMeter.rawRequestTokens(emptyList(), tools = request.tools)
        val pending = pendingAttachmentTokensForBreakdown()
        val measured = _lastTurnContextTokens.value.takeIf { it > 0 }
        val est = if (measured != null) {
            ContextTokenEstimate(measured, measured)
        } else {
            val local = sections.sumOf { it.second } + toolTokens + historyTokens + pending
            ContextTokenEstimate(local, null)
        }
        ContextBreakdownMath.build(
            sections = sections,
            historyTokens = historyTokens,
            pendingAttachmentTokens = pending,
            usedTokens = est.tokens,
            windowTokens = currentModelContextWindow ?: 0,
            maxOutputTokens = currentModelMaxOutputTokens ?: 0,
            toolSchemaTokens = toolTokens,
            measuredPrefixTokens = est.measuredPrefixTokens,
        )
    }

/**
 * Map the `#`-heading sections of the assembled system prompt onto the UI's
 * semantic keys.
 *
 * The prompt is assembled in a fixed order (identity/tools → skills → memory →
 * dynamic tail → goal/plan), and [ContextAssemblySnapshot] already splits it on
 * `^#`. Matching on the heading text is the only signal available without
 * re-cutting `buildSystemPrompt()`, so the classifier is deliberately
 * permissive: an unrecognised heading lands in [ContextBlockKey.SYSTEM_TOOLS],
 * which keeps the sum correct even if a future fragment is added.
 */
internal fun classifyPromptSection(label: String): ContextBlockKey {
    val l = label.lowercase()
    return when {
        l.contains("skill") || l.contains("mcp") -> ContextBlockKey.SKILLS_MCPS
        l.contains("memory") || l.contains("global.md") -> ContextBlockKey.MEMORY
        l.contains("goal") || l.contains("plan") || l.contains("work report") ->
            ContextBlockKey.GOAL_PLAN
        l.contains("worldbook") || l.contains("world book") || l.contains("anchor") ||
            l.contains("runtime") || l.contains("preference") || l.contains("learned") ->
            ContextBlockKey.RUNTIME_TAIL
        else -> ContextBlockKey.SYSTEM_TOOLS
    }
}

/**
 * Token cost per semantic block of the assembled system prompt.
 *
 * The snapshot keeps `(label, charLength)` pairs and the full text, not the
 * per-section text. Rather than rebuild fake section bodies (which would
 * mis-measure: a CJK section and an ASCII section of equal char length differ
 * by >2x in tokens), each section's token cost is apportioned from the
 * prompt's REAL token count by char share. That keeps the row sum equal to the
 * whole-prompt measurement, which is what the ±10% label promises.
 *
 * Returns one whole-prompt row when no snapshot exists yet (a session that has
 * never sent a turn) or when the snapshot belongs to another session.
 */
internal fun systemPromptSectionTokensForBreakdown(
    systemPrompt: String,
): List<Pair<ContextBlockKey, Int>> {
    if (systemPrompt.isEmpty()) return emptyList()
    val snapshot = ContextAssemblySnapshot.latest
    val totalTokens = ContextTokenMeter.rawTextTokens(systemPrompt)
    // Two independent reasons the snapshot length will NOT equal the prompt we
    // just assembled:
    //  1. `capture` stores `SecretMasking.mask(rawText)`, and masking replaces
    //     matched credentials with a fixed token, so a prompt that carried a
    //     secret is shorter in the snapshot.
    //  2. the snapshot is the LAST turn's assembly; the dynamic tail (runtime
    //     context, timestamps) differs from this turn's by a few dozen chars.
    // Neither invalidates the section PROPORTIONS, which is all this function
    // uses, so compare with a tolerance instead of demanding equality. Beyond
    // that tolerance the snapshot is probably another session entirely and the
    // whole-prompt fallback is the honest answer.
    val snapshotChars = snapshot?.totalChars ?: 0
    val drift = kotlin.math.abs(snapshotChars - systemPrompt.length)
    val withinTolerance = snapshot != null &&
        snapshot.sections.isNotEmpty() &&
        drift <= (systemPrompt.length * SNAPSHOT_LENGTH_TOLERANCE).toInt().coerceAtLeast(64)
    if (!withinTolerance) {
        return listOf(ContextBlockKey.SYSTEM_TOOLS to totalTokens)
    }
    val totalChars = snapshot!!.sections.sumOf { it.second }.coerceAtLeast(1)
    val byKey = LinkedHashMap<ContextBlockKey, Long>()
    for ((label, chars) in snapshot.sections) {
        val key = classifyPromptSection(label)
        byKey[key] = (byKey[key] ?: 0L) + chars.toLong()
    }
    return byKey.map { (key, chars) ->
        key to (chars * totalTokens.toLong() / totalChars).toInt().coerceAtLeast(0)
    }
}

/** Allowed relative drift between the snapshot's text and a freshly built prompt. */
private const val SNAPSHOT_LENGTH_TOLERANCE = 0.05

/** The request that WOULD be sent now: effective history + assembled prompt + tools. */
internal fun ChatViewModel.projectedRequestForBreakdown(): ChatRequest {
    val history = effectiveAgentHistoryUncounted()
    val tools = runCatching { agentTools }.getOrDefault(emptyList())
    return ChatRequest(
        messages = history,
        systemPrompt = runCatching { buildSystemPrompt() }.getOrNull(),
        maxTokens = 1,
        tools = tools,
    )
}

/** Token cost of composer attachments that have not been sent yet. */
internal fun ChatViewModel.pendingAttachmentTokensForBreakdown(): Int {
    val pending = runCatching { _attachments.value }.getOrNull().orEmpty()
    if (pending.isEmpty()) return 0
    var tokens = 0
    for (a in pending) {
        tokens += if (a.isImage) ContextTokenMeter.IMAGE_TOKENS else ContextTokenMeter.rawTextTokens(a.fileName)
    }
    return tokens
}
