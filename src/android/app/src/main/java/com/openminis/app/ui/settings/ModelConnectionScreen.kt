package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.data.repository.AutoModelGroups
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.theme.IosBlue

/**
 * [T-model-connection] 「模型与连接」聚合页：把设置里原本并列的「供应商管理」
 * 和「模型组」两个入口收成一个，下面按三段组织：
 *
 *  - 连接：供应商数量 + 模型数量，进入供应商列表；
 *  - 路由：用户自建组数量 + 隐式组数量（只读），进入模型组；
 *  - 默认槽位：主模型 / 子模型 / 视觉 / 语音输入输出 / 压缩回退的当前绑定，
 *    进入模型组页编辑（槽位编辑器在那里）。
 *
 * 不复制任何配置逻辑：所有数字与名称都从 [ProviderRepository] 现读，页面只
 * 负责导航与概览，避免第三份真相。
 */
@Composable
fun ModelConnectionScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onProvidersClick: () -> Unit,
    onGroupsClick: () -> Unit,
) {
    val config by providerRepository.config.collectAsStateWithLifecycle()

    val providerCount = config.instances.size
    val modelCount = config.modelEntries.count { !it.isHidden }
    val groupCount = AutoModelGroups.userGroups(config).size
    val autoGroupCount = config.modelGroups.count { AutoModelGroups.isAutoId(it.id) }

    val notSet = stringResource(R.string.model_connection_not_set)
    fun slot(raw: String?): String = providerRepository.slotDisplayName(raw) ?: notSet

    SettingsScaffold(
        title = stringResource(R.string.model_connection_title),
        onBack = onBack,
    ) {
        SettingsSection(header = stringResource(R.string.settings_section_llm_providers)) {
            SettingsRow(
                icon = Icons.Outlined.Lock,
                iconColor = IosBlue,
                title = stringResource(R.string.settings_manage_providers),
                subtitle = stringResource(
                    R.string.model_connection_providers_count,
                    providerCount,
                    modelCount,
                ),
                onClick = onProvidersClick,
                showDivider = false,
            )
        }

        SettingsSection(header = stringResource(R.string.settings_model_groups)) {
            SettingsRow(
                icon = Icons.Outlined.Settings,
                iconColor = IosBlue,
                title = stringResource(R.string.settings_model_groups),
                subtitle = stringResource(R.string.model_connection_groups_count, groupCount),
                onClick = onGroupsClick,
            )
            // 隐式组由供应商派生，只展示不编辑，避免出现「删了又回来」的困惑。
            SettingsValueRow(
                title = stringResource(R.string.model_connection_auto_groups),
                value = autoGroupCount.toString(),
                showDivider = false,
            )
        }

        SettingsSection(header = stringResource(R.string.model_groups_defaults)) {
            SettingsValueRow(
                title = stringResource(R.string.model_groups_default_primary),
                value = slot(providerRepository.defaultPrimaryGroupId),
                onClick = onGroupsClick,
            )
            SettingsValueRow(
                title = stringResource(R.string.model_groups_vision),
                value = slot(providerRepository.visionGroupId),
                onClick = onGroupsClick,
            )
            SettingsValueRow(
                title = stringResource(R.string.model_groups_voice_input),
                value = slot(providerRepository.voiceInputGroupId),
                onClick = onGroupsClick,
            )
            SettingsValueRow(
                title = stringResource(R.string.model_groups_voice_output),
                value = slot(providerRepository.voiceOutputGroupId),
                onClick = onGroupsClick,
            )
            SettingsValueRow(
                title = stringResource(R.string.model_groups_compact_fallback),
                value = slot(providerRepository.compactFallbackGroupId),
                onClick = onGroupsClick,
                showDivider = false,
            )
        }
    }
}
