package com.openminis.app.mcp.client

import com.openminis.app.data.repository.MCPRepository.MCPServerConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinMcpServerTest {
    private fun builtin(port: Int = 8765): MCPServerConfig = BuiltinMcpServer.merge(emptyList(), true, port).single()

    @Test
    fun enabledServiceIsAvailableWithoutSavedServers() {
        val server = builtin()
        assertEquals(BuiltinMcpServer.ID, server.id)
        assertEquals("http://127.0.0.1:8765/mcp", server.url)
        assertTrue(server.enabled)
        assertTrue(server.isBuiltin)
        assertTrue(server.headers.isEmpty())
        assertTrue(server.note!!.contains("lemon_list"))
        assertTrue(server.note!!.contains("lemon_call"))
        assertTrue(server.note!!.length <= 200)
    }

    @Test
    fun disabledServiceDoesNotAddAnEntry() {
        val configured = listOf(MCPServerConfig("remote", url = "https://example.com/mcp"))
        assertSame(configured, BuiltinMcpServer.merge(configured, false, 8765))
    }

    @Test
    fun existingLoopbackEntryIsPreservedWithoutDuplicates() {
        val configured = listOf(
            MCPServerConfig("phone", note = "custom", enabled = false, url = "http://127.0.0.1:8765/mcp"),
        )
        assertSame(configured, BuiltinMcpServer.merge(configured, true, 8765))
        assertFalse(configured.single().isBuiltin)
    }

    @Test
    fun localhostEntryAlsoPreventsDuplicates() {
        val configured = listOf(MCPServerConfig("phone", url = "http://localhost:8765/mcp"))
        assertSame(configured, BuiltinMcpServer.merge(configured, true, 8765))
    }

    @Test
    fun unrelatedIdsAndEndpointsAreNotOverwritten() {
        val configured = listOf(
            MCPServerConfig(BuiltinMcpServer.ID, url = "https://example.com/mcp"),
            MCPServerConfig(BuiltinMcpServer.ID + "_2", url = "http://127.0.0.1:9999/mcp"),
        )
        val merged = BuiltinMcpServer.merge(configured, true, 8765)
        assertEquals(BuiltinMcpServer.ID + "_3", merged.first().id)
        assertEquals(configured, merged.drop(1))
    }

    @Test
    fun stdioConfigDoesNotShadowTheBuiltInService() {
        val configured = listOf(
            MCPServerConfig("stdio", command = "python3", url = "http://127.0.0.1:8765/mcp"),
        )
        assertEquals(2, BuiltinMcpServer.merge(configured, true, 8765).size)
    }

    @Test
    fun malformedUrlsDoNotBreakDiscovery() {
        val configured = listOf(MCPServerConfig("broken", url = "not a URL"))
        assertEquals(2, BuiltinMcpServer.merge(configured, true, 8765).size)
    }

    @Test
    fun runtimeEntryIsStableAndTracksConfiguredPort() {
        assertEquals(builtin(), builtin())
        assertEquals("http://127.0.0.1:8877/mcp", builtin(8877).url)
        val wired = BuiltinLoopbackResolver.resolve(builtin().url!!, 8765, 8766, true)
        assertEquals("http://127.0.0.1:8766/mcp", (wired as BuiltinLoopbackResolver.Outcome.Wired).effectiveUrl)
    }

    @Test
    fun automaticEntryExposesOnlyTheCatalogTools() {
        val server = builtin()
        assertTrue(BuiltinMcpServer.allowsTool(server, "lemon_list"))
        assertTrue(BuiltinMcpServer.allowsTool(server, "lemon_call"))
        assertFalse(BuiltinMcpServer.allowsTool(server, "shell_exec"))
        assertFalse(BuiltinMcpServer.allowsTool(server, "file_write"))
    }

    @Test
    fun descriptorFilterHonorsThePerToolSwitch() {
        val tools = JSONArray()
            .put(JSONObject().put("name", "shell_exec"))
            .put(JSONObject().put("name", "lemon_list"))
            .put(JSONObject().put("name", "lemon_call"))
        val filtered = BuiltinMcpServer.filterTools(builtin(), tools, setOf("lemon_call"))
        assertEquals(1, filtered.length())
        assertEquals("lemon_list", filtered.getJSONObject(0).getString("name"))
    }

    @Test
    fun explicitlyConfiguredServersKeepTheirOriginalToolSurface() {
        val server = MCPServerConfig("remote", url = "https://example.com/mcp")
        val tools = JSONArray().put(JSONObject().put("name", "shell_exec"))
        assertTrue(BuiltinMcpServer.allowsTool(server, "shell_exec"))
        assertSame(tools, BuiltinMcpServer.filterTools(server, tools))
        assertEquals(0, BuiltinMcpServer.filterTools(server, tools, setOf("shell_exec")).length())
    }

    @Test
    fun permissionIdentityIsIndependentOfTheRuntimeAlias() {
        val ordinary = builtin()
        val configured = listOf(MCPServerConfig(BuiltinMcpServer.ID, url = "https://example.com/mcp"))
        val aliased = BuiltinMcpServer.merge(configured, true, 8765).first()
        assertEquals(BuiltinMcpServer.policyId(ordinary), BuiltinMcpServer.policyId(aliased))
        assertEquals(BuiltinMcpServer.ID, BuiltinMcpServer.policyId(configured.single()))
    }
}
