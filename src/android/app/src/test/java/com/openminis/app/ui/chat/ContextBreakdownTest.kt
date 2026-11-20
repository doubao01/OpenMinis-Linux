package com.openminis.app.ui.chat

import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the estimated composition of the next request, separately from the
 * verified request total. Ported from the upstream `ContextBreakdownTest` with
 * one signature change: this tree's [ContextBreakdownMath.build] takes
 * pre-measured `(key, tokens)` pairs, because the system-prompt sections come
 * from [com.openminis.app.agent.ContextAssemblySnapshot] char shares rather
 * than from per-section text.
 */
class ContextBreakdownTest {

    private val sections = listOf(
        ContextBlockKey.SYSTEM_TOOLS to 1_800,
        ContextBlockKey.SKILLS_MCPS to 220,
        ContextBlockKey.MEMORY to 640,
        ContextBlockKey.RUNTIME_TAIL to 90,
        ContextBlockKey.GOAL_PLAN to 130,
    )

    private val historyTokens = 4_321
    private val pendingAttachments = ContextTokenMeter.IMAGE_TOKENS * 2

    @Test
    fun `total is exactly the sum of the emitted blocks`() {
        val breakdown = ContextBreakdownMath.build(
            sections = sections,
            historyTokens = historyTokens,
            pendingAttachmentTokens = pendingAttachments,
            usedTokens = 10_000,
            windowTokens = 200_000,
            maxOutputTokens = 8_192,
            toolSchemaTokens = 900,
        )
        assertEquals(
            "totalTokens must equal the sum of blocks (the sheet's percentages add to 100%)",
            breakdown.blocks.sumOf { it.tokens },
            breakdown.totalTokens,
        )
    }

    @Test
    fun `shares sum to one`() {
        val breakdown = ContextBreakdownMath.build(
            sections = sections,
            historyTokens = historyTokens,
            pendingAttachmentTokens = pendingAttachments,
            usedTokens = 10_000,
            windowTokens = 200_000,
            maxOutputTokens = 8_192,
            toolSchemaTokens = 900,
        )
        val shareSum = breakdown.blocks.sumOf { it.share.toDouble() }
        assertEquals(1.0, shareSum, 0.0001)
    }

    @Test
    fun `zero contributors produce no rows`() {
        val breakdown = ContextBreakdownMath.build(
            sections = listOf(ContextBlockKey.SYSTEM_TOOLS to 0),
            historyTokens = 0,
            pendingAttachmentTokens = 0,
            usedTokens = 0,
            windowTokens = 0,
            maxOutputTokens = 0,
            toolSchemaTokens = 0,
        )
        assertTrue("an empty request must not render rows", breakdown.blocks.isEmpty())
        assertEquals(0, breakdown.totalTokens)
        assertEquals(0f, breakdown.blocks.sumOf { it.share.toDouble() }.toFloat(), 0.0001f)
    }

    @Test
    fun `pending attachments get their own row not folded into history`() {
        val breakdown = ContextBreakdownMath.build(
            sections = emptyList(),
            historyTokens = 1_000,
            pendingAttachmentTokens = pendingAttachments,
            usedTokens = 0,
            windowTokens = 0,
            maxOutputTokens = 0,
            toolSchemaTokens = 0,
        )
        val keys = breakdown.blocks.map { it.key }
        assertEquals(listOf(ContextBlockKey.HISTORY, ContextBlockKey.PENDING_ATTACHMENTS), keys)
        assertEquals(1_000, breakdown.blocks.first { it.key == ContextBlockKey.HISTORY }.tokens)
        assertEquals(
            pendingAttachments,
            breakdown.blocks.first { it.key == ContextBlockKey.PENDING_ATTACHMENTS }.tokens,
        )
    }

    @Test
    fun `tool schemas are charged once per request`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "shell_execute",
                description = "Run a shell command in the Linux sandbox.",
                parameters = emptyMap(),
            ),
        )
        val measured = ContextTokenMeter.rawRequestTokens(emptyList(), tools = tools)
        assertTrue("a non-empty tool catalogue must cost something", measured > 0)

        val breakdown = ContextBreakdownMath.build(
            sections = emptyList(),
            historyTokens = 0,
            pendingAttachmentTokens = 0,
            usedTokens = 0,
            windowTokens = 0,
            maxOutputTokens = 0,
            toolSchemaTokens = measured,
        )
        assertEquals(measured, breakdown.blocks.single { it.key == ContextBlockKey.TOOL_SCHEMAS }.tokens)
    }

    @Test
    fun `prompt section classifier maps known headings`() {
        assertEquals(ContextBlockKey.SKILLS_MCPS, classifyPromptSection("Available skills"))
        assertEquals(ContextBlockKey.SKILLS_MCPS, classifyPromptSection("MCP servers"))
        assertEquals(ContextBlockKey.MEMORY, classifyPromptSection("Memory system (currently ENABLED)"))
        assertEquals(ContextBlockKey.GOAL_PLAN, classifyPromptSection("Goal context"))
        assertEquals(ContextBlockKey.GOAL_PLAN, classifyPromptSection("Plan board"))
        assertEquals(ContextBlockKey.RUNTIME_TAIL, classifyPromptSection("Runtime context"))
        assertEquals(ContextBlockKey.RUNTIME_TAIL, classifyPromptSection("WorldBook"))
        assertEquals(ContextBlockKey.RUNTIME_TAIL, classifyPromptSection("Session task anchor"))
        assertEquals(ContextBlockKey.SYSTEM_TOOLS, classifyPromptSection("You are Minis Ultra"))
        assertEquals(ContextBlockKey.SYSTEM_TOOLS, classifyPromptSection("(section)"))
    }

    @Test
    fun `meter counts cjk heavier than ascii of equal char length`() {
        val ascii = "a".repeat(400)
        val cjk = "中".repeat(400)
        assertTrue(
            "CJK must cost more tokens than ASCII at equal char count",
            ContextTokenMeter.rawTextTokens(cjk) > ContextTokenMeter.rawTextTokens(ascii),
        )
    }

    @Test
    fun `meter charges images a flat allowance`() {
        val withImage = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            imageParts = listOf(
                LLMMessage.ImagePart(data = ByteArray(64) { 1 }, mimeType = "image/png"),
            ),
        )
        assertEquals(ContextTokenMeter.IMAGE_TOKENS, ContextTokenMeter.rawMessageTokens(withImage))
    }

    @Test
    fun `tool use part counts its input json`() {
        val small = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolUse(
                    id = "t1",
                    name = "shell_execute",
                    input = JSONObject().put("command", "ls"),
                ),
            ),
        )
        val large = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolUse(
                    id = "t1",
                    name = "shell_execute",
                    input = JSONObject().put("command", "x".repeat(2_000)),
                ),
            ),
        )
        assertTrue(
            "a bigger tool input must measure bigger",
            ContextTokenMeter.rawMessageTokens(large) > ContextTokenMeter.rawMessageTokens(small),
        )
    }

    @Test
    fun `fingerprint is stable for equal payloads and differs for changed ones`() {
        fun req(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)
        val a = ContextTokenMeter.requestFingerprint(listOf(req("hello")), systemPrompt = "sys")
        val b = ContextTokenMeter.requestFingerprint(listOf(req("hello")), systemPrompt = "sys")
        val c = ContextTokenMeter.requestFingerprint(listOf(req("hello!")), systemPrompt = "sys")
        assertEquals(a, b)
        assertTrue("changed payload must change the fingerprint", a != c)
    }
}
