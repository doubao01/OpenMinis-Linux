package com.openminis.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.mcp.client.BuiltinMcpServer
import com.openminis.app.mcp.client.McpJsonRpc
import com.openminis.app.mcp.client.McpNativeBridge
import com.openminis.app.mcp.server.McpServerConfigStore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BuiltinMcpRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: MCPRepository
    private lateinit var configFile: File
    private var builtin = McpServerConfigStore.Config(enabled = true, token = "never-save-this-token")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        configFile = File(context.filesDir, "minis-global/mcp-servers/servers.json")
        configFile.parentFile!!.mkdirs()
        configFile.writeText(
            """{"mcpServers":{"remote":{"url":"https://example.com/mcp","enabled":false,"createdAt":1}}}""",
        )
        repository = MCPRepository(context) { builtin }
        repository.load()
    }

    @Test
    fun agentOverviewAndPromptDiscoverTheRuntimeEntry() {
        assertEquals(listOf("remote"), repository.servers.value.map { it.id })
        val overview = McpNativeBridge.serversOverview(repository, "chat")
        assertTrue(overview.contains("minis_builtin [enabled]"))
        val fragment = repository.mcpPromptFragment("chat")!!
        assertTrue(fragment.contains("minis_builtin"))
        assertTrue(fragment.contains("lemon_list"))
        assertTrue(fragment.contains("lemon_call"))
        assertFalse(fragment.contains("never-save-this-token"))
    }

    @Test
    fun sessionCanDisableTheCatalogWithoutChangingOtherSessions() {
        repository.setSessionOverride("chat", BuiltinMcpServer.ID, false)
        assertFalse(repository.isEnabledForSession(BuiltinMcpServer.ID, "chat"))
        assertFalse(repository.effectiveEnabledMap("chat").getValue(BuiltinMcpServer.ID))
        assertNull(repository.mcpPromptFragment("chat"))
        assertTrue(repository.isEnabledForSession(BuiltinMcpServer.ID, "other-chat"))
        assertTrue(repository.mcpPromptFragment("other-chat")!!.contains("minis_builtin"))
    }

    @Test
    fun restoringTheGlobalDefaultClearsTheSessionOverride() {
        repository.setSessionOverride("chat", BuiltinMcpServer.ID, false)
        repository.setSessionOverride("chat", BuiltinMcpServer.ID, true)
        assertFalse(repository.sessionOverrideMap("chat").containsKey(BuiltinMcpServer.ID))
        assertTrue(repository.effectiveEnabledMap("chat").getValue(BuiltinMcpServer.ID))
    }

    @Test
    fun serviceDisableInvalidatesThePromptAndCannotBeOverriddenByTheSession() {
        assertTrue(repository.mcpPromptFragment("chat")!!.contains("minis_builtin"))
        builtin = builtin.copy(enabled = false)
        repository.setSessionOverride("chat", BuiltinMcpServer.ID, true)
        assertNull(repository.mcpPromptFragment("chat"))
        assertFalse(repository.effectiveEnabledMap("chat").containsKey(BuiltinMcpServer.ID))
        assertFalse(repository.isEnabledForSession(BuiltinMcpServer.ID, "chat"))
        builtin = builtin.copy(enabled = true)
        assertTrue(repository.mcpPromptFragment("chat")!!.contains("minis_builtin"))
    }

    @Test
    fun changedPortIsVisibleWithoutEditingSavedConfigs() {
        val before = configFile.readText()
        repository.mcpPromptFragment("chat")
        builtin = builtin.copy(port = 8877)
        val server = repository.availableServers().first { it.isBuiltin }
        assertEquals("http://127.0.0.1:8877/mcp", server.url)
        assertEquals(before, configFile.readText())
    }

    @Test
    fun savingAUserServerDoesNotPersistTheRuntimeEntryOrToken() {
        repository.mcpPromptFragment("chat")
        repository.setEnabled("remote", true)
        val saved = configFile.readText()
        val servers = JSONObject(saved).getJSONObject("mcpServers")
        assertTrue(servers.has("remote"))
        assertFalse(servers.has(BuiltinMcpServer.ID))
        assertFalse(saved.contains("never-save-this-token"))
    }

    @Test
    fun sessionDisabledCatalogIsRejectedBeforeTransportConstruction() {
        repository.setSessionOverride("chat", BuiltinMcpServer.ID, false)
        val error = runCatching {
            McpNativeBridge.callTool(context, repository, "chat", BuiltinMcpServer.ID, "lemon_list", JSONObject())
        }.exceptionOrNull() as McpJsonRpc.McpError
        assertEquals("NOT_FOUND", error.code)
    }

    @Test
    fun automaticEntryCannotInvokeOtherBuiltInServerTools() {
        val error = runCatching {
            McpNativeBridge.callTool(context, repository, "chat", BuiltinMcpServer.ID, "shell_exec", JSONObject())
        }.exceptionOrNull() as McpJsonRpc.McpError
        assertEquals("TOOL_DISABLED", error.code)
    }

    @Test
    fun sessionDisableSurvivesAConflictChangingTheRuntimeAlias() {
        repository.setSessionOverride("chat", BuiltinMcpServer.ID, false)
        configFile.writeText(
            """{"mcpServers":{"minis_builtin":{"url":"https://example.com/mcp","enabled":false,"createdAt":1}}}""",
        )
        repository.reloadFromDisk()
        val server = repository.availableServers().first { it.isBuiltin }
        assertEquals(BuiltinMcpServer.ID + "_2", server.id)
        assertFalse(repository.isEnabledForSession(server.id, "chat"))
        assertFalse(repository.effectiveEnabledMap("chat").getValue(server.id))
        assertNull(repository.mcpPromptFragment("chat"))
        assertTrue(repository.isEnabledForSession(server.id, "other-chat"))
    }
}
