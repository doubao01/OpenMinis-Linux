package com.openminis.app.mcp.client

import com.openminis.app.data.repository.MCPRepository.MCPServerConfig
import org.json.JSONArray

object BuiltinMcpServer {
    const val ID = "minis_builtin"
    const val POLICY_ID = "@builtin:lemon"
    const val AGENT_DISCOVERY_NOTE = "Native mcp only. Lemon phone tools: system, files, apps, network, kernel, debug. " +
        "Search lemon_list by keyword/domain, read its params/required, then execute lemon_call. Device prerequisites apply."
    val TOOL_NAMES: Set<String> = setOf("lemon_list", "lemon_call")

    fun merge(configured: List<MCPServerConfig>, enabled: Boolean, port: Int): List<MCPServerConfig> {
        if (!enabled || configured.any { targetsBuiltin(it, port) }) return configured
        val takenIds = configured.map { it.id }.toSet()
        var serverId = ID
        var suffix = 2
        while (serverId in takenIds) {
            serverId = ID + "_" + suffix
            suffix++
        }
        return listOf(
            MCPServerConfig(
                id = serverId,
                note = AGENT_DISCOVERY_NOTE,
                url = "http://127.0.0.1:" + port + "/mcp",
                createdAt = 0L,
                isBuiltin = true,
            ),
        ) + configured
    }

    private fun targetsBuiltin(server: MCPServerConfig, port: Int): Boolean {
        if (server.isStdio) return false
        val url = server.url ?: return false
        return BuiltinLoopbackResolver.resolve(url, port, port, true) is BuiltinLoopbackResolver.Outcome.Wired
    }

    fun allowsTool(server: MCPServerConfig, tool: String): Boolean = !server.isBuiltin || tool in TOOL_NAMES

    fun policyId(server: MCPServerConfig): String = if (server.isBuiltin) POLICY_ID else server.id

    fun filterTools(server: MCPServerConfig, tools: JSONArray, disabled: Set<String> = emptySet()): JSONArray {
        if (!server.isBuiltin && disabled.isEmpty()) return tools
        return JSONArray().apply {
            for (index in 0 until tools.length()) {
                val descriptor = tools.optJSONObject(index) ?: continue
                val name = descriptor.optString("name")
                if (name !in disabled && allowsTool(server, name)) put(descriptor)
            }
        }
    }
}
