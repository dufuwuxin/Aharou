package com.aharou.feature.localmodel.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.localmodel.domain.LocalModelState
import com.aharou.feature.localmodel.domain.LocalModels
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import com.aharou.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Download
import java.util.Locale

/**
 * 设置页「本地模型（离线推理）」分节：点一下下载 → 自动下载并安装到本机。
 *
 * 视觉完全沿用设置页既有分组件（[SettingsGroupHeader] + [SettingsGroup] + [SettingsRow] + [SettingsDivider]），
 * 不自造行样式，免得这一节跟上下分组看起来不是一套。
 *
 * 一个模型一行，行内副行按优先级显示：下载中 → 校验中 → 已就绪 → 失败原因 → 未下完 → 未下载；
 * 行下一行小字给「体积 + 内存建议」，让用户点下载前就知道要花多少空间。
 */
@Composable
internal fun LocalModelSection() {
    // 设置页是 Activity 级作用域，分节自己取 VM 即可；hiltViewModel 会命中同一个宿主
    val vm: LocalModelViewModel = hiltViewModel()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsGroupHeader(text = stringResource(R.string.settings_local_model_section_title))

    // 非 arm64 设备连推理库都加载不了，列出来只会让人白下一次 1GB
    if (!vm.supported) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Download,
                title = stringResource(R.string.settings_local_model_not_supported),
                enabled = false
            )
        }
        return
    }

    Text(
        text = stringResource(R.string.settings_local_model_section_desc),
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm)
    )

    SettingsGroup {
        LocalModels.ALL.forEachIndexed { index, spec ->
            if (index > 0) SettingsDivider()

            val status = statuses.firstOrNull { it.id == spec.id }
            val downloading = (state as? LocalModelState.Downloading)?.takeIf { it.id == spec.id }
            val verifying = (state as? LocalModelState.Verifying)?.takeIf { it.id == spec.id }
            val failed = (state as? LocalModelState.Failed)?.takeIf { it.id == spec.id }
            val ready = status?.ready == true
            // 下载中/校验中：右侧按钮置灰，避免重复触发（VM 里也挡了一层）
            val busy = downloading != null || verifying != null

            SettingsRow(
                icon = FeatherIcons.Download,
                title = spec.displayName,
                subtitle = when {
                    downloading != null -> stringResource(
                        R.string.settings_local_model_downloading,
                        (downloading.progress * 100).toInt()
                    )
                    verifying != null -> stringResource(R.string.settings_local_model_verifying)
                    ready -> stringResource(R.string.settings_local_model_ready)
                    failed != null -> if (failed.message.isBlank()) {
                        stringResource(R.string.settings_local_model_failed)
                    } else {
                        stringResource(R.string.settings_local_model_failed_detail, failed.message)
                    }
                    status != null && status.partial -> stringResource(
                        R.string.settings_local_model_partial,
                        formatBytes(status.partialBytes ?: 0L)
                    )
                    else -> stringResource(R.string.settings_local_model_not_downloaded)
                },
                trailing = {
                    when {
                        ready -> TextButton(onClick = { vm.delete(spec.id) }) {
                            Text(
                                text = stringResource(R.string.settings_local_model_delete),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                        busy -> Text(
                            text = stringResource(R.string.settings_local_model_download),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        else -> TextButton(onClick = { vm.download(spec.id) }) {
                            Text(
                                text = stringResource(R.string.settings_local_model_download),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }
                }
            )

            // 与上一行标题文字对齐：行内左内边距 + 图标宽 + 图标后间距
            Text(
                text = formatBytes(spec.sizeBytes) + " · " +
                    stringResource(R.string.settings_local_model_ram_hint, spec.minRamGb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = Spacing.lg + 16.dp + Spacing.md,
                    end = Spacing.lg,
                    bottom = Spacing.sm
                )
            )
        }
    }
}

/** 体积可读化：MB / GB 一位小数（与下载器落盘日志的口径一致）。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= GIB -> String.format(Locale.US, "%.1f GB", bytes / GIB.toDouble())
    bytes >= MIB -> String.format(Locale.US, "%.1f MB", bytes / MIB.toDouble())
    else -> String.format(Locale.US, "%.1f KB", bytes / KIB.toDouble())
}

private const val KIB = 1024L
private const val MIB = 1024L * 1024L
private const val GIB = 1024L * 1024L * 1024L
