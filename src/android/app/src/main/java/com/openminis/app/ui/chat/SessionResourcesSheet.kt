package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.ui.tokens.RawSpace
import com.openminis.app.security.SessionPermissionMode

/**
 * 会话资源：把原来菜单里的 4 个入口（技能 / MCP / 记忆 / 工具台）
 * 与 3 个实验性开关收进一张表，让三点菜单从 13 项收敛到 7 项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionResourcesSheet(
    viewModel: ChatViewModel,
    onOpenSkills: () -> Unit,
    onOpenMcps: () -> Unit,
    onOpenTokenUsage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fastMode by viewModel.fastModeEnabled.collectAsStateWithLifecycle()
    val autoCompact by viewModel.autoCompactEnabled.collectAsStateWithLifecycle()
    val permMode by viewModel.permissionMode.collectAsStateWithLifecycle()
    val cacheOn by viewModel.enhancedCacheEnabled.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = RawSpace.xxl3.dp),
        ) {
            Text(
                text = stringResource(R.string.session_res_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = RawSpace.xxl3.dp, end = RawSpace.xxl3.dp, bottom = RawSpace.md.dp),
            )
            Text(
                text = stringResource(R.string.session_res_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = RawSpace.xxl3.dp, end = RawSpace.xxl3.dp, bottom = RawSpace.lg.dp),
            )

            ResourceRow(stringResource(R.string.session_res_skills), stringResource(R.string.session_res_skills_sub)) {
                onDismiss(); onOpenSkills()
            }
            ResourceRow(stringResource(R.string.session_res_mcp), stringResource(R.string.session_res_mcp_sub)) {
                onDismiss(); onOpenMcps()
            }
            ResourceRow(stringResource(R.string.session_res_memory), stringResource(R.string.session_res_memory_sub)) {
                onDismiss(); viewModel.toggleMemorySheet()
            }
            ResourceRow(stringResource(R.string.session_res_tokens), stringResource(R.string.session_res_tokens_sub)) {
                onDismiss(); onOpenTokenUsage()
            }
            ResourceRow(stringResource(R.string.session_res_lemon), stringResource(R.string.session_res_lemon_sub)) {
                onDismiss()
                viewModel.openBrowserSheetForUrl("http://127.0.0.1:8795/")
            }

            Spacer(Modifier.height(RawSpace.md.dp))
            HorizontalDivider(Modifier.padding(horizontal = RawSpace.xxl3.dp))
            Text(
                text = stringResource(R.string.session_res_experimental),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = RawSpace.xxl3.dp, top = RawSpace.xl.dp, bottom = RawSpace.xs.dp),
            )

            ResourceRow(
                title = stringResource(
                    R.string.session_res_perm_title,
                    stringResource(
                        when (permMode) {
                            SessionPermissionMode.ASK_BEFORE_CHANGES -> R.string.session_res_perm_ask
                            SessionPermissionMode.AUTONOMOUS -> R.string.session_res_perm_auto
                            else -> R.string.session_res_perm_full
                        },
                    ),
                ),
                desc = stringResource(R.string.session_res_perm_hint),
            ) {
                viewModel.setSessionPermissionMode(
                    when (permMode) {
                        SessionPermissionMode.ASK_BEFORE_CHANGES -> SessionPermissionMode.AUTONOMOUS
                        SessionPermissionMode.AUTONOMOUS -> SessionPermissionMode.FULL_ACCESS
                        else -> SessionPermissionMode.ASK_BEFORE_CHANGES
                    },
                )
            }
            SwitchRow(
                title = stringResource(R.string.session_res_cache_title),
                desc = stringResource(R.string.session_res_cache_sub),
                checked = cacheOn,
                onChange = { on ->
                    if (on) viewModel.confirmAndEnableEnhancedCache()
                    else viewModel.setEnhancedCacheEnabled(false)
                },
            )
            SwitchRow(
                title = stringResource(R.string.session_res_fast_title),
                desc = stringResource(R.string.session_res_fast_sub),
                checked = fastMode,
                onChange = { viewModel.setFastModeEnabled(it) },
            )
            SwitchRow(
                title = stringResource(R.string.session_res_compact_title),
                desc = stringResource(R.string.session_res_compact_sub),
                checked = autoCompact,
                onChange = { viewModel.setAutoCompactEnabled(it) },
            )

            Spacer(Modifier.height(RawSpace.md.dp))
            TextButton(
                onClick = { onDismiss() },
                modifier = Modifier.padding(start = RawSpace.lg.dp),
            ) { Text(stringResource(R.string.session_res_close)) }
        }
    }
}

@Composable
private fun ResourceRow(title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = RawSpace.lg.dp)
            .padding(vertical = RawSpace.xxs.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RawSpace.lg.dp),
    ) {
        TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = RawSpace.xxl3.dp, vertical = RawSpace.md.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
