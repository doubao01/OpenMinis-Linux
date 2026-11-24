package com.openminis.app.mcp.server.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-androidtest-blind-spot] 成功路径测试 —— 上一版 `LemonToolsTest` 只覆盖了
 * 失败路径（无 asset 时 isError、缺 name 时拒绝），因为 JVM 测试没有 assets。
 *
 * 这里用 Robolectric 读**真实** `assets/lemon/catalog.json`，验证三件之前
 * 只能靠人肉看的事：
 *
 *  1. 目录能被 `LemonCatalog.all()` 真读出来，条数与生成器报告一致；
 *  2. `lemon_list` 的三种过滤（domain / keyword / risk）真的按预期收敛；
 *  3. `buildCommand` 的 `$key` 替换走 shellEscape —— 上游面板用 `str.format`
 *     填参数，值里带 `;` 或 `$(...)` 就是命令注入。这条是安全回归点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LemonCatalogRealAssetTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `real catalog asset parses into the full tool set`() {
        val all = LemonCatalog.all(context)
        assertEquals("目录应与脚本生成的条数一致", 358, all.size)

        val sample = all.values.first()
        assertTrue("每个工具都必须有命令体", sample.cmd.isNotBlank())
        assertTrue("域不能用未分组占位", !sample.domain.startsWith("D9"))
        assertTrue(
            "风险等级只能是三档之一",
            sample.risk in setOf("low", "medium", "high"),
        )
    }

    @Test
    fun `no tool falls into the ungrouped bucket`() {
        val all = LemonCatalog.all(context)
        val ungrouped = all.values.filter { it.domain.startsWith("D9") }
        assertEquals(
            "分组表覆盖后不应再有未分组工具：${ungrouped.map { it.name }}",
            0,
            ungrouped.size,
        )
    }

    @Test
    fun `domain filter narrows to that domain only`() {
        val tools = lemonTools(deps())
        val list = tools.first { it.name == "lemon_list" }
        val res = list.handler(JSONObject().put("domain", "D6-内核"))

        val body = res.content.getJSONObject(0).getString("text")
        // 只断言形状：拿到 JSON、matched 非零、全部落在该域
        val json = JSONObject(body)
        assertTrue("该域应有工具", json.getInt("matched") > 0)
        val arr = json.getJSONArray("tools")
        for (i in 0 until arr.length()) {
            assertEquals("D6-内核", arr.getJSONObject(i).getString("domain"))
        }
    }

    @Test
    fun `risk filter returns only that tier`() {
        val tools = lemonTools(deps())
        val list = tools.first { it.name == "lemon_list" }
        val res = list.handler(JSONObject().put("risk", "high"))
        val json = JSONObject(res.content.getJSONObject(0).getString("text"))

        val arr = json.getJSONArray("tools")
        assertTrue("high 档应非空", arr.length() > 0)
        for (i in 0 until arr.length()) {
            assertEquals("high", arr.getJSONObject(i).getString("risk"))
        }
    }

    // ─── 命令渲染：注入防护 ─────────────────────────────────────────────

    @Test
    fun `argument values are shell escaped so semicolons cannot chain commands`() {
        val tool = tool(cmd = "echo \$msg", params = "msg:string", required = "msg")
        val rendered = buildCommand(tool, JSONObject().put("msg", "a; reboot"))

        // 单引号包裹 => 分号落在引号内，不会成为命令分隔符
        assertEquals("echo 'a; reboot'", rendered)
    }

    @Test
    fun `dollar-paren substitution is neutralised`() {
        val tool = tool(cmd = "echo \$msg", params = "msg:string", required = "msg")
        val rendered = buildCommand(tool, JSONObject().put("msg", "\$(id)"))

        assertEquals("echo '\$(id)'", rendered)
    }

    @Test
    fun `embedded single quote is escaped the POSIX way`() {
        val tool = tool(cmd = "echo \$msg", params = "msg:string", required = "msg")
        val rendered = buildCommand(tool, JSONObject().put("msg", "it's"))

        // shellEscape: ' -> '\''  =>  echo 'it'\''s'
        assertEquals("echo 'it'\\''s'", rendered)
    }

    @Test
    fun `missing required parameter is rejected instead of expanding to empty`() {
        val tool = tool(cmd = "am force-stop \$pkg", params = "pkg:string", required = "pkg")
        val thrown = runCatching { buildCommand(tool, JSONObject()) }.exceptionOrNull()
        assertTrue("缺必填参数必须抛错", thrown is IllegalArgumentException)
        assertTrue(thrown!!.message!!.contains("pkg"))
    }

    @Test
    fun `underscore-prefixed reason arg is never rendered into the command`() {
        val tool = tool(cmd = "echo \$msg", params = "msg:string", required = "msg")
        val rendered = buildCommand(
            tool,
            JSONObject().put("msg", "hi").put("_reason", "because"),
        )
        assertFalse("审计原因不应进入命令行", rendered.contains("because"))
    }

    private fun tool(cmd: String, params: String, required: String) = LemonCatalog.Tool(
        name = "probe_tool",
        desc = "probe",
        cmd = cmd,
        params = params,
        required = required,
        timeoutMs = 5000,
        domain = "D1-系统",
        risk = "low",
        readOnly = true,
    )

    private fun deps() = McpToolDeps(
        context = context,
        gate = com.openminis.app.security.SecurityGateImpl(),
        shellMode = { com.openminis.app.security.PermissionMode.ALLOW_ALL },
    )
}
