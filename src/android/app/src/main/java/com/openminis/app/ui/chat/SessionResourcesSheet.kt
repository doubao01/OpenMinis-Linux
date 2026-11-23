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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.security.PermissionMode

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
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "会话资源",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            Text(
                text = "只作用于当前会话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )

            ResourceRow("技能", "当前会话可用的技能包") {
                onDismiss(); onOpenSkills()
            }
            ResourceRow("MCP", "已接入的 MCP 服务器与工具") {
                onDismiss(); onOpenMcps()
            }
            ResourceRow("记忆", "本会话写入的日记与召回") {
                onDismiss(); viewModel.toggleMemorySheet()
            }
            ResourceRow("Token 用量", "本会话的输入/输出与缓存命中") {
                onDismiss(); onOpenTokenUsage()
            }
            ResourceRow("小柠檬工具台", "363 个系统工具 · 面板") {
                onDismiss()
                viewModel.openBrowserSheetForUrl("http://127.0.0.1:8795/")
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            Text(
                text = "实验性",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
            )

            ResourceRow(
                title = "权限模式：" + when (permMode) {
                    PermissionMode.ASK -> "逐次审批"
                    PermissionMode.SAFE_AUTO -> "部分授权"
                    else -> "全授权"
                },
                desc = "点按切换：审批 → 部分授权 → 全授权",
            ) {
                viewModel.setSessionPermissionMode(
                    when (permMode) {
                        PermissionMode.ASK -> PermissionMode.SAFE_AUTO
                        PermissionMode.SAFE_AUTO -> PermissionMode.ALLOW_ALL
                        else -> PermissionMode.ASK
                    },
                )
            }
            SwitchRow(
                title = "增强缓存",
                desc = "仅对 Anthropic 供应商生效，按会话前缀缓存",
                checked = cacheOn,
                onChange = { on ->
                    if (on) viewModel.confirmAndEnableEnhancedCache()
                    else viewModel.setEnhancedCacheEnabled(false)
                },
            )
            SwitchRow(
                title = "高速模式",
                desc = "仅对支持 priority processing 的供应商生效（OpenAI / xAI）",
                checked = fastMode,
                onChange = { viewModel.setFastModeEnabled(it) },
            )
            SwitchRow(
                title = "自动压缩",
                desc = "上下文接近上限时自动折叠早期消息",
                checked = autoCompact,
                onChange = { viewModel.setAutoCompactEnabled(it) },
            )

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { onDismiss() },
                modifier = Modifier.padding(start = 12.dp),
            ) { Text("关闭") }
        }
    }
}

@Composable
private fun ResourceRow(title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
            .padding(horizontal = 24.dp, vertical = 8.dp),
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
