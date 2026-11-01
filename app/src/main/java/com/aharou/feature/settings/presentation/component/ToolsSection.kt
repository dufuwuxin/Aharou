package com.aharou.feature.settings.presentation.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.BatchDeleteConfirmDialog
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.agent.domain.tool.custom.ToolInjectionMode
import com.aharou.feature.settings.presentation.ToolSource
import com.aharou.feature.settings.presentation.ToolUiEntry
import com.aharou.feature.settings.presentation.ToolsMessage
import com.aharou.feature.settings.presentation.ToolsUiState
import com.aharou.feature.settings.presentation.ToolsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Server
import compose.icons.feathericons.Tool
import compose.icons.feathericons.Zap

/**
 * 工具管理页：按「自定义 / 内置 / MCP」三组列出全部工具，点行开详情面板。
 *
 * 新建入口在设置页顶栏的「＋」（与子代理页一致），这里只管列表与详情；
 * 自定义工具可左滑删除（内置与 MCP 工具删不掉，左滑按钮置灰），
 * 内置 / MCP 的「删除」语义是「清掉对它的描述与注入方式覆盖」，统一在详情面板里做。
 */
@Composable
internal fun ToolsSection(
    state: ToolsUiState,
    viewModel: ToolsViewModel
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<ToolUiEntry?>(null) }

    // 失败必须看得见：写盘失败不静默刷新，弹一次 Toast 后把事件清掉
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        Toast.makeText(
            context,
            context.getString(
                when (message) {
                    ToolsMessage.SAVE_FAILED -> R.string.tools_save_failed
                    ToolsMessage.DELETE_FAILED -> R.string.tools_delete_failed
                    ToolsMessage.LOAD_FAILED -> R.string.tools_load_failed
                }
            ),
            Toast.LENGTH_LONG
        ).show()
        viewModel.clearMessage()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.entries.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.tools_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
                        )
                    }
                }
                TOOL_GROUP_ORDER.forEach { source ->
                    val entries = state.entries.filter { it.source == source }.sortedBy { it.name.lowercase() }
                    if (entries.isEmpty()) return@forEach
                    item(key = "tools-header-${source.name}") {
                        SettingsGroupHeader(text = stringResource(groupTitleRes(source)))
                    }
                    items(entries, key = { "${source.name}:${it.name}" }) { entry ->
                        ToolRow(
                            entry = entry,
                            onClick = { viewModel.openEditor(entry.name) },
                            onDelete = { pendingDelete = entry }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.tools_delete_title),
            message = stringResource(R.string.tools_delete_confirm, entry.name),
            onConfirm = {
                pendingDelete = null
                viewModel.deleteCustomTool(entry.name)
            },
            onDismiss = { pendingDelete = null }
        )
    }

    val editor = state.editor
    if (editor != null) {
        ToolEditorSheet(
            entry = editor.name?.let { name -> state.entries.firstOrNull { it.name == name } },
            saving = state.saving,
            onDismiss = viewModel::closeEditor,
            onSaveCustom = { name, description, parameters, command, timeout, mode, keywords ->
                viewModel.saveCustomTool(name, description, command, parameters, timeout, mode, keywords)
            },
            onSaveSettings = { name, description, mode, keywords ->
                viewModel.saveToolSettings(name, description, mode, keywords)
            }
        )
    }
}

/** 一行工具：名称 + 描述 + 注入方式徽标；自定义工具可左滑删除。 */
@Composable
private fun ToolRow(
    entry: ToolUiEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick,
        deleteEnabled = entry.source == ToolSource.CUSTOM
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.md, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = sourceIcon(entry.source),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.description.ifBlank { stringResource(R.string.tools_no_description) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            InjectionBadge(mode = entry.injectionMode)
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 注入方式徽标：关闭态整体置灰，一眼看出「这工具现在不给模型用」。 */
@Composable
private fun InjectionBadge(mode: ToolInjectionMode) {
    val tint = when (mode) {
        ToolInjectionMode.OFF -> MaterialTheme.semanticColors.subtleText
        ToolInjectionMode.KEYWORD -> MaterialTheme.colorScheme.tertiary
        ToolInjectionMode.EVERY_ROUND -> MaterialTheme.colorScheme.primary
    }
    Text(
        text = stringResource(injectionLabelRes(mode)),
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

private val TOOL_GROUP_ORDER = listOf(ToolSource.CUSTOM, ToolSource.BUILTIN, ToolSource.MCP)

private fun groupTitleRes(source: ToolSource): Int = when (source) {
    ToolSource.CUSTOM -> R.string.tools_group_custom
    ToolSource.BUILTIN -> R.string.tools_group_builtin
    ToolSource.MCP -> R.string.tools_group_mcp
}

private fun injectionLabelRes(mode: ToolInjectionMode): Int = when (mode) {
    ToolInjectionMode.EVERY_ROUND -> R.string.tools_inject_every_round
    ToolInjectionMode.KEYWORD -> R.string.tools_inject_keyword
    ToolInjectionMode.OFF -> R.string.tools_inject_off
}

private fun sourceIcon(source: ToolSource) = when (source) {
    ToolSource.CUSTOM -> FeatherIcons.Tool
    ToolSource.BUILTIN -> FeatherIcons.Zap
    ToolSource.MCP -> FeatherIcons.Server
}

/**
 * 配置页「工具」子页：顶栏（返回 / 标题 / 新建） + [ToolsSection] 列表。
 *
 * 不做成设置页的分区：设置页那个文件已经贴着行数基线（棘轮只许降），再塞一整套页面接线只会抬基线。
 * 这里自带返回箭头 + [BackHandler]（在调用方的 ConfigScreen 里），行为与设置页子页一致。
 */
@Composable
internal fun ToolsPage(onBack: () -> Unit) {
    val viewModel: ToolsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.xs, end = Spacing.lg, top = Spacing.md, bottom = Spacing.sm)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = FeatherIcons.ArrowLeft,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = stringResource(R.string.settings_tools),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { viewModel.startCreate() }) {
                Icon(
                    imageVector = FeatherIcons.Plus,
                    contentDescription = stringResource(R.string.tools_add),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        ToolsSection(state = state, viewModel = viewModel)
    }
}
