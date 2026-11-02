package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.agent.domain.mcp.McpServerStatus
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.settings.presentation.ToolCategory
import com.aharou.feature.settings.presentation.ToolsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Package
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Users

/**
 * 设置 → 工具：把 agent 当前真正注册着的工具单列成一页，和「记忆」「技能」同级。
 *
 * 只读：工具是编译期注册进 [com.aharou.feature.agent.domain.tool.ToolRegistry] 的能力，
 * 能不能用由权限策略在运行时决定，页面不提供开关，免得做出一个「关了但 agent 照样能调」的假开关。
 */
@Composable
internal fun ToolsSection(
    viewModel: ToolsViewModel = hiltViewModel()
) {
    val grouped = viewModel.grouped()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl)
    ) {
        Text(
            text = stringResource(R.string.settings_tools_desc, viewModel.entries.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        )

        grouped.forEach { (category, entries) ->
            SettingsGroupHeader(text = stringResource(category.labelRes()))
            SettingsGroup {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        icon = category.icon(),
                        title = entry.name,
                        subtitle = entry.description,
                        trailing = {
                            Text(
                                text = stringResource(
                                    if (entry.permission == ToolPermissionPolicy.ASK) R.string.settings_tools_need_approval
                                    else R.string.settings_tools_auto
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (entry.permission == ToolPermissionPolicy.ASK) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        }

        if (grouped.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_tools_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xl)
            )
        }
    }
}

/** 分类的标题文案。 */
private fun ToolCategory.labelRes(): Int = when (this) {
    ToolCategory.FILE -> R.string.settings_tools_category_file
    ToolCategory.COMMAND -> R.string.settings_tools_category_command
    ToolCategory.NETWORK -> R.string.settings_tools_category_network
    ToolCategory.DEVICE -> R.string.settings_tools_category_device
    ToolCategory.AGENT -> R.string.settings_tools_category_agent
    ToolCategory.EXTERNAL -> R.string.settings_tools_category_external
    ToolCategory.OTHER -> R.string.settings_tools_category_other
}

/** 分类的图标：每类固定一个，扫列表时靠形状就能分堆。 */
private fun ToolCategory.icon() = when (this) {
    ToolCategory.FILE -> FeatherIcons.FileText
    ToolCategory.COMMAND -> FeatherIcons.Terminal
    ToolCategory.NETWORK -> FeatherIcons.Globe
    ToolCategory.DEVICE -> FeatherIcons.Smartphone
    ToolCategory.AGENT -> FeatherIcons.Users
    ToolCategory.EXTERNAL -> FeatherIcons.Package
    ToolCategory.OTHER -> FeatherIcons.Tool
}

/**
 * MCP 服务器管理区：可开关、删除。编辑入口复用 McpSection 的现有编辑页。
 *
 * @param entries 当前已配置的 MCP 服务器列表
 * @param statuses 连接状态（与 entries 一一对应，按名字匹配）
 * @param onToggle 开关某台 server
 * @param onDelete 删除某台 server
 * @param onEdit 编辑某台 server（跳转 McpSection 的编辑页）
 */
@Composable
internal fun McpToolsSection(
    entries: List<McpServerEntry>,
    statuses: List<McpServerStatus>,
    onToggle: (McpServerEntry, Boolean) -> Unit,
    onDelete: (McpServerEntry) -> Unit,
    onEdit: (McpServerEntry) -> Unit
) {
    if (entries.isEmpty()) return
    SettingsGroupHeader(text = stringResource(R.string.settings_mcp))
    SettingsGroup {
        entries.forEachIndexed { index, entry ->
            if (index > 0) SettingsDivider()
            val status = statuses.firstOrNull { it.name == entry.server.name }
            SettingsRow(
                icon = if (entry.server.isStdio) FeatherIcons.Terminal else FeatherIcons.Globe,
                title = entry.server.name,
                subtitle = entry.server.command ?: entry.server.url,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        // 状态点
                        val state = status?.state ?: McpServerStatus.State.DISABLED
                        val color = when (state) {
                            McpServerStatus.State.CONNECTED -> MaterialTheme.colorScheme.primary
                            McpServerStatus.State.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(color)
                        )
                        // 开关
                        AppSwitch(
                            checked = entry.server.enabled,
                            onCheckedChange = { onToggle(entry, it) },
                            enabled = true
                        )
                        // 删除
                        IconButton(
                            onClick = { onDelete(entry) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.Trash2,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            )
        }
    }
}
