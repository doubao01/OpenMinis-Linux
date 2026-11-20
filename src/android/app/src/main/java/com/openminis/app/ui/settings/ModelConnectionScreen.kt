package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.SectionDesign

/**
 * [T-model-connection] 「模型与连接」聚合页：设置里原本并列的「供应商管理」和
 * 「模型组」两个入口收成一个页面，用分段控件切换两段内容。
 *
 * 两个标签页直接复用 [ProviderListScreen] / [ModelGroupsScreen] 本体，不复制
 * 任何列表或配置逻辑——顶栏动作（同步全部、新建组）因此原样保留，避免出现
 * 「合并入口后某个操作悄悄消失」。
 */
@Composable
fun ModelConnectionScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onProviderClick: (String) -> Unit,
    onVoiceServiceClick: (String) -> Unit,
    onGroupClick: (String) -> Unit,
    onAddAgentLoopModels: () -> Unit,
    onAddAgentLoopGroups: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SectionDesign.screenBackgroundColor()),
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedButton(
                selected = tab == 0,
                onClick = { tab = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.settings_manage_providers)) }
            SegmentedButton(
                selected = tab == 1,
                onClick = { tab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.settings_model_groups)) }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                0 -> ProviderListScreen(
                    providerRepository = providerRepository,
                    onBack = onBack,
                    onAddProvider = onAddProvider,
                    onProviderClick = onProviderClick,
                    onVoiceServiceClick = onVoiceServiceClick,
                )
                else -> ModelGroupsScreen(
                    providerRepository = providerRepository,
                    onBack = onBack,
                    onGroupClick = onGroupClick,
                    onAddAgentLoopModels = onAddAgentLoopModels,
                    onAddAgentLoopGroups = onAddAgentLoopGroups,
                )
            }
        }
    }
}
