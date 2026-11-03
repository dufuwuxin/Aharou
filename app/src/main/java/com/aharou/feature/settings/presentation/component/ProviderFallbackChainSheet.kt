package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.FallbackTarget
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
/**
 * 备用模型链编辑：替换原来的「单选一个回退供应商」。
 *
 * 语义就是列表顺序：主供应商失败先切第 1 条，第 1 条也失败切第 2 条，依此往下轮询；
 * 每条都是「供应商 + 模型」的组合，所以同一供应商换模型、换供应商都只是链上的不同条。
 *
 * 三种视图共用同一个弹窗（链 / 选供应商 / 选模型），避免再叠一层弹窗：
 * 底部弹窗套底部弹窗在 Compose 里会互相抢 scrim 与返回键。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderFallbackChainSheet(
    providers: List<AIProviderConfig>,
    chain: List<FallbackTarget>,
    onChange: (List<FallbackTarget>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    /** 0 = 看链，1 = 选供应商，2 = 选模型。 */
    var stage by remember { mutableIntStateOf(0) }
    var pickedProviderId by remember { mutableStateOf("") }
    var manualModel by remember { mutableStateOf("") }

    fun providerName(id: String) = providers.firstOrNull { it.id == id }?.name ?: id

    fun append(providerId: String, model: String) {
        val entry = FallbackTarget(providerId, model.trim())
        if (entry !in chain) onChange(chain + entry)
        stage = 0
        manualModel = ""
    }

    fun move(from: Int, to: Int) {
        if (from !in chain.indices || to !in chain.indices) return
        val updated = chain.toMutableList()
        updated.add(to, updated.removeAt(from))
        onChange(updated)
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = when (stage) {
                    1 -> stringResource(R.string.provider_fallback_pick_provider)
                    2 -> stringResource(R.string.provider_fallback_pick_model)
                    else -> stringResource(R.string.provider_fallback_title)
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xs)
            )
            Text(
                text = stringResource(R.string.provider_fallback_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            when (stage) {
                1 -> ProviderPickerList(providers = providers, onPick = {
                    pickedProviderId = it
                    manualModel = ""
                    stage = 2
                })

                2 -> ModelPickerList(
                    provider = providers.firstOrNull { it.id == pickedProviderId },
                    manualModel = manualModel,
                    onManualModelChange = { manualModel = it },
                    onPick = { append(pickedProviderId, it) },
                    onPickManual = { append(pickedProviderId, manualModel) }
                )

                else -> ChainEditor(
                    chain = chain,
                    providerName = ::providerName,
                    onMove = ::move,
                    onRemove = { onChange(chain.filterIndexed { index, _ -> index != it }) },
                    onAdd = { stage = 1 }
                )
            }
        }
    }
}

/** 链本体：序号 + 「供应商 · 模型」+ 上移 / 下移 / 删除。 */
@Composable
private fun ChainEditor(
    chain: List<FallbackTarget>,
    providerName: (String) -> String,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
        if (chain.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.provider_fallback_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
                )
            }
        }
        itemsIndexed(chain, key = { index, item -> "$index-${item.providerId}-${item.model}" }) { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = providerName(item.providerId),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.model.ifBlank { stringResource(R.string.provider_fallback_use_current) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                    Icon(FeatherIcons.ArrowUp, contentDescription = stringResource(R.string.provider_fallback_move_up),
                        modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { onMove(index, index + 1) }, enabled = index < chain.lastIndex) {
                    Icon(FeatherIcons.ArrowDown, contentDescription = stringResource(R.string.provider_fallback_move_down),
                        modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { onRemove(index) }) {
                    Icon(FeatherIcons.Trash2, contentDescription = stringResource(R.string.common_delete),
                        modifier = Modifier.size(16.dp))
                }
            }
        }
        item(key = "add") {
            SettingsRow(
                icon = FeatherIcons.Plus,
                title = stringResource(R.string.provider_fallback_add),
                onClick = onAdd
            )
        }
    }
}

/** 第一步：选供应商（已排除当前供应商，自己指自己会形成自环）。 */
@Composable
private fun ProviderPickerList(providers: List<AIProviderConfig>, onPick: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
        if (providers.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.provider_fallback_no_provider),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
                )
            }
        }
        itemsIndexed(providers, key = { _, item -> item.id }) { _, provider ->
            SettingsRow(
                title = provider.name,
                subtitle = provider.effectiveModel.takeIf { it.isNotBlank() },
                onClick = { onPick(provider.id) }
            )
        }
    }
}

/** 第二步：选模型——列该供应商已添加的模型，也可以留空跟随它自己的当前模型，或直接手输模型名。 */
@Composable
private fun ModelPickerList(
    provider: AIProviderConfig?,
    manualModel: String,
    onManualModelChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onPickManual: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
        item(key = "current") {
            SettingsRow(
                title = stringResource(R.string.provider_fallback_use_current),
                subtitle = provider?.effectiveModel?.takeIf { it.isNotBlank() },
                onClick = { onPick("") }
            )
        }
        itemsIndexed(provider?.models ?: emptyList(), key = { _, model -> model }) { _, model ->
            SettingsRow(title = model, onClick = { onPick(model) })
        }
        item(key = "manual") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                AppTextField(
                    value = manualModel,
                    onValueChange = onManualModelChange,
                    label = stringResource(R.string.provider_fallback_manual),
                    singleLine = true
                )
                SettingsRow(
                    icon = FeatherIcons.Plus,
                    title = stringResource(R.string.provider_fallback_add),
                    enabled = manualModel.isNotBlank(),
                    onClick = onPickManual
                )
            }
        }
    }
}

/** 链的展示串（设置页行副标题用）：「供应商A · 模型 → 供应商B」。 */
internal fun fallbackChainSummary(chain: List<FallbackTarget>, providerName: (String) -> String): String =
    chain.joinToString(" → ") { item ->
        val name = providerName(item.providerId)
        if (item.model.isBlank()) name else "$name · ${item.model}"
    }
