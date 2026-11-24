package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.mcp.server.McpServerManager

@Composable
internal fun rememberAvailableMcpServers(repository: MCPRepository): List<MCPRepository.MCPServerConfig> {
    val configured by repository.servers.collectAsStateWithLifecycle()
    val state by McpServerManager.state.collectAsStateWithLifecycle()
    val builtin = McpServerManager.configStore()?.config?.collectAsStateWithLifecycle()?.value
    return remember(repository, configured, builtin?.enabled, builtin?.port, state.status) {
        repository.availableServers(configured)
    }
}
