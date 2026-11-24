package com.openminis.app.ui.lemon

import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.mcp.server.McpServerConfigStore
import com.openminis.app.mcp.server.McpServerManager
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-lemon-integration] The single settings row that launches the bundled tool
 * panel.
 *
 * Lives here rather than inline in `MCPIntegrationsScreen`: that screen is on
 * the architecture baseline, and three lines of new UI are enough to break it.
 * Keeping the row next to the panel it opens also means the launch contract
 * (start the server if it is off, then navigate with the live port) has one
 * home instead of being re-derived by every caller.
 */
@Composable
internal fun LemonPanelLauncherRow(
    port: Int,
    onEnsureServer: () -> Int?,
    onOpen: (Int) -> Unit,
) {
    SettingsRow(
        title = stringResource(R.string.mcp_lemon_panel),
        subtitle = stringResource(R.string.mcp_lemon_panel_sub, port),
        // Apps is already in the M3Icons table (M3IconsTest maps every m3Icon
        // call site); Widgets would need a new (Filled, Outlined) pair.
        icon = m3Icon(Icons.Outlined.Apps),
        showDivider = false,
        onClick = {
            // The panel's WebView must only load after the socket is accepting
            // connections; otherwise its first fetch fails and it stays on the
            // "not connected" screen even though the server just started.
            onEnsureServer()?.let(onOpen)
        },
    )
}

/**
 * Ensure the built-in MCP server is listening and return the port it actually
 * bound. A fallback port is possible, so the panel must use the live port, not
 * the configured one.
 */
internal fun ensureBuiltInMcpServer(
    context: Context,
    store: McpServerConfigStore?,
): Int? {
    McpServerManager.actualPort()?.let { return it }
    store?.setEnabled(true)
    if (!McpServerManager.start()) {
        Toast.makeText(
            context,
            context.getString(
                R.string.mcp_start_failed_toast,
                McpServerManager.state.value.error
                    ?: context.getString(R.string.mcp_error_unknown),
            ),
            Toast.LENGTH_SHORT,
        ).show()
        return null
    }
    return McpServerManager.actualPort()
}
