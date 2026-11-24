package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-perm-matrix] 三档权限 × 风险等级的**全矩阵**回归。
 *
 * 为什么单独一个文件：`SessionPermissionDecideTest` 原先只有 3 个用例，
 * 覆盖 ASK 与 YOYO 两端，**中间的 GUARDED（自主模式）完全没测** ——
 * 而"自主模式只拦 high、其余自动跑"正是 `SessionPermissionMode.AUTONOMOUS`
 * 的全部语义。柠檬工具的 358 个风险等级就是按这三档设计的：
 *
 *     变更前确认 ASK      → medium + high 都要确认
 *     自主模式   GUARDED  → 只拦 DANGEROUS
 *     完全访问   YOYO     → 一个都不拦（fatal 除外，且 fatal 是 mustPrompt）
 *
 * 如果 GUARDED 的分支没有测试，这条语义就没有回归保护 —— 有人把
 * `risk == DANGEROUS` 误改成 `risk != NORMAL`，线上就会变成"自主模式
 * 和变更前确认一样啰嗦"，而所有门禁仍然是绿的。
 *
 * 本文件只断言 `Decision` 的类型，不碰文案（文案是 i18n 资源的事）。
 */
class PermissionModeMatrixTest {

    private val gate = SecurityGateImpl()

    // ── 工具夹具：覆盖 decide 的几条主分支 ──────────────────────────────

    private fun read() = gate.classify("file_read", """{"path":"/var/minis/workspace/a.txt"}""")
    private fun write() = gate.classify("file_write", """{"path":"/var/minis/workspace/a.txt","content":"x"}""")
    private fun shellSafe() = gate.classify("shell_exec", """{"command":"echo hi"}""")
    private fun shellDangerous() = gate.classify("shell_exec", """{"command":"rm -rf /var/minis/workspace/x"}""")
    private fun shellFatal() = gate.classify("shell_exec", """{"command":"rm -rf /"}""")
    private fun coordinator() = gate.classify("agent_plan", """{"op":"list"}""")

    // ── GUARDED：三档里的中间档，此前零覆盖 ─────────────────────────────

    @Test
    fun guardedAutoRunsOrdinaryWork() {
        for (cmd in listOf(read(), write(), shellSafe(), coordinator())) {
            val d = gate.decide(cmd, PermissionMode.GUARDED)
            assertTrue(
                "GUARDED 应自动放行普通操作，实际 ${d::class.simpleName} for ${cmd.toolName}",
                d is Decision.Allow,
            )
        }
    }

    @Test
    fun guardedStopsOnlyDangerousCommands() {
        val d = gate.decide(shellDangerous(), PermissionMode.GUARDED)
        assertTrue(
            "GUARDED 必须拦住 DANGEROUS，实际 ${d::class.simpleName}",
            d is Decision.NeedConfirm,
        )
    }

    @Test
    fun guardedAndAskDifferOnMediumRisk() {
        // 这条是「自主模式 vs 变更前确认」的分界线：同样是写入，
        // ASK 要确认、GUARDED 直接放行。两个档位若在此处给出同一答案，
        // 说明其中一档退化了。
        val w = write()
        assertTrue("ASK 对写入应确认", gate.decide(w, PermissionMode.ASK) is Decision.NeedConfirm)
        assertTrue("GUARDED 对写入应放行", gate.decide(w, PermissionMode.GUARDED) is Decision.Allow)
    }

    // ── ASK：只读自动放行、写入确认 ─────────────────────────────────────

    @Test
    fun askAutoRunsReadsButConfirmsWrites() {
        assertTrue(gate.decide(read(), PermissionMode.ASK) is Decision.Allow)
        assertTrue(gate.decide(write(), PermissionMode.ASK) is Decision.NeedConfirm)
    }

    @Test
    fun askAutoRunsOnlyAllowListedShellCommands() {
        // `echo` 在 SAFE_COMMANDS 里 -> ASK 下自动放行；不在白名单的解释器调用
        // 必须问。这条把「安全命令白名单」和「一切含 shell 就要问」区分开 ——
        // 后者会让 ASK 模式对每条 `ls` 都弹窗，是实际踩过的体验问题。
        assertTrue(
            "白名单命令应自动放行",
            gate.decide(shellSafe(), PermissionMode.ASK) is Decision.Allow,
        )
        val unknown = gate.classify(
            "shell_exec",
            """{"command":"python3 -c 'print(1)'"}""",
        )
        assertTrue(
            "非白名单命令应确认",
            gate.decide(unknown, PermissionMode.ASK) is Decision.NeedConfirm,
        )
    }

    // ── ASK_ALL：连只读都问 ────────────────────────────────────────────

    @Test
    fun askAllPromptsEvenForReads() {
        val d = gate.decide(read(), PermissionMode.ASK_ALL)
        assertTrue(
            "ASK_ALL 承诺「每次调用都问」，只读也不例外，实际 ${d::class.simpleName}",
            d is Decision.NeedConfirm || d is Decision.Denied,
        )
    }

    @Test
    fun askAllPromptsForCoordinatorTools() {
        val d = gate.decide(coordinator(), PermissionMode.ASK_ALL)
        assertTrue(
            "ASK_ALL 下协调工具也不该自动放行，实际 ${d::class.simpleName}",
            d is Decision.NeedConfirm || d is Decision.Denied,
        )
    }

    // ── YOYO：除 fatal 之外全放 ────────────────────────────────────────

    @Test
    fun yoyoAutoRunsEverythingExceptFatal() {
        for (cmd in listOf(read(), write(), shellSafe(), shellDangerous(), coordinator())) {
            val d = gate.decide(cmd, PermissionMode.ALLOW_ALL)
            assertTrue(
                "YOYO 应自动放行 ${cmd.toolName}，实际 ${d::class.simpleName}",
                d is Decision.Allow,
            )
        }
    }

    @Test
    fun yoyoStillPromptsOnFatal() {
        val d = gate.decide(shellFatal(), PermissionMode.ALLOW_ALL)
        assertTrue("fatal 在 YOYO 下必须弹确认", d is Decision.NeedConfirm)
        assertTrue("fatal 的确认必须是强制弹窗", (d as Decision.NeedConfirm).mustPrompt)
    }

    // ── 只读 / 计划模式：禁止写与执行 ──────────────────────────────────

    @Test
    fun readOnlyAndPlanBlockWritesAndShell() {
        for (mode in listOf(PermissionMode.READ_ONLY, PermissionMode.PLAN)) {
            for (cmd in listOf(write(), shellSafe())) {
                val d = gate.decide(cmd, mode)
                assertTrue(
                    "$mode 应拒绝 ${cmd.toolName}，实际 ${d::class.simpleName}",
                    d is Decision.Denied,
                )
            }
        }
    }

    @Test
    fun readOnlyStillAllowsReads() {
        val d = gate.decide(read(), PermissionMode.READ_ONLY)
        assertTrue("READ_ONLY 应放行只读", d is Decision.Allow)
    }

    // ── DENY_ALL：全拒，只读也不例外 ──────────────────────────────────

    @Test
    fun denyAllBlocksEvenReads() {
        val d = gate.decide(read(), PermissionMode.DENY_ALL)
        assertTrue(
            "DENY_ALL 连只读都拒，实际 ${d::class.simpleName}",
            d is Decision.Denied,
        )
    }

    // ── 会话档位 → 门模式的映射是单点，验证它没被改散 ─────────────────

    @Test
    fun sessionModesCompileToTheGateModesThePanelAssumes() {
        assertEquals(PermissionMode.ASK, SessionPermissionMode.ASK_BEFORE_CHANGES.toGateMode())
        assertEquals(PermissionMode.GUARDED, SessionPermissionMode.AUTONOMOUS.toGateMode())
        assertEquals(PermissionMode.ALLOW_ALL, SessionPermissionMode.FULL_ACCESS.toGateMode())
    }

    @Test
    fun onlyFullAccessCountsAsAutomaticTopTier() {
        assertTrue(SessionPermissionMode.FULL_ACCESS.isFullAccess())
        assertTrue(!SessionPermissionMode.ASK_BEFORE_CHANGES.isAutomatic())
        assertTrue(SessionPermissionMode.AUTONOMOUS.isAutomatic())
        assertTrue(SessionPermissionMode.FULL_ACCESS.isAutomatic())
    }
}
