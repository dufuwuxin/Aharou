package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.ui.BatchDeleteConfirmDialog
import com.aharou.core.ui.SelectionAction
import com.aharou.core.ui.SelectionActionBar
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.core.ui.rememberListSelectionState
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.core.ui.AppTextField
import com.aharou.feature.settings.presentation.groupSkillsByTag
import com.aharou.feature.settings.presentation.skillSelectionId
import com.aharou.feature.settings.presentation.matchesSkillQuery
import com.aharou.feature.settings.presentation.SkillUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.Archive
import compose.icons.feathericons.Book
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Trash2

/**
 * 技能二级页：按标签与分类分组，作用域筛选独立保留。
 * 每行一个技能（图标 + 名称 + 描述），点击行进入详情。
 * 用户可管理的两组支持左滑删除；长按任一行弹操作单（搬运 / 导出 / 删除，内置技能只有导出）。
 * 装到哪个作用域由右上角「＋」里的选择决定（与市场安装共用）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillsSection(
    projectName: String?,
    entries: List<SkillUiEntry>,
    onDelete: (SkillUiEntry) -> Unit,
    onMove: (String, SkillScope, SkillScope) -> Unit,
    onExport: (SkillUiEntry) -> Unit,
    onOpenDetail: (SkillUiEntry) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var scopeFilter by rememberSaveable { mutableStateOf<SkillScope?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        AppTextField(value = query, onValueChange = { query = it },
            placeholder = stringResource(R.string.skills_search_hint),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), singleLine = true)
        SkillTypeManager()
        FlowRow(modifier = Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(selected = scopeFilter == null, onClick = { scopeFilter = null },
                label = { Text(stringResource(R.string.skills_scope_all)) })
            SkillScope.entries.forEach { scope ->
                FilterChip(selected = scopeFilter == scope, onClick = { scopeFilter = scope }, label = {
                    Text(stringResource(when (scope) {
                        SkillScope.BUILTIN -> R.string.skills_builtin
                        SkillScope.PROJECT -> R.string.skills_scope_project
                        SkillScope.GLOBAL -> R.string.skills_scope_global
                    }))
                })
            }
        }
        val filtered = entries.filter { (scopeFilter == null || it.scope == scopeFilter) && it.matchesSkillQuery(query) }
        if (entries.isNotEmpty() && filtered.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.skills_search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Box(Modifier.weight(1f)) {
            SkillsListContent(projectName = projectName, entries = filtered, onDelete = onDelete,
                onMove = onMove, onExport = onExport, onOpenDetail = onOpenDetail)
        }
    }
}

@Composable
private fun SkillsListContent(
    projectName: String?,
    entries: List<SkillUiEntry>,
    onDelete: (SkillUiEntry) -> Unit,
    onMove: (String, SkillScope, SkillScope) -> Unit,
    onExport: (SkillUiEntry) -> Unit,
    onOpenDetail: (SkillUiEntry) -> Unit
) {
    val groups = remember(entries) { groupSkillsByTag(entries) }

    if (entries.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xl, vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.lg)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        FeatherIcons.Book,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.skills_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.skills_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    var collapsedTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // 长按某一行弹出的操作单；null 表示没弹
    var actionEntry by remember { mutableStateOf<SkillUiEntry?>(null) }

    val settingsViewModel = rememberSettingsViewModel()
    val selection = rememberListSelectionState()
    // 选中 id 带作用域：同名技能可能项目与全局各一份；内置技能不在候选里，全选也不会把它们算进去
    val selectionIds = entries.filterNot { it.builtin }.map(::skillSelectionId).distinct()
    LaunchedEffect(selectionIds) { selection.retain(selectionIds) }
    var showBatchDelete by remember { mutableStateOf(false) }
    // 内置技能随 App 打包，不可删也不参与多选
    val selectableCount = entries.count { !it.builtin }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg),
        contentPadding = PaddingValues(bottom = Spacing.xl)
    ) {
        if (selection.selectionMode) {
            item(key = "skills_selection_bar", contentType = "batch_entry") {
                SelectionActionBar(
                    selectedCount = selection.selectedCount,
                    allSelected = selection.isAllSelected(selectionIds),
                    onToggleSelectAll = { selection.toggleSelectAll(selectionIds) },
                    onExit = { selection.exit() },
                    modifier = Modifier.padding(bottom = Spacing.sm),
                    actions = listOf(
                        SelectionAction(
                            icon = FeatherIcons.Trash2,
                            label = stringResource(R.string.common_delete),
                            onClick = { showBatchDelete = true },
                            destructive = true
                        )
                    )
                )
            }
        } else if (selectableCount > 0) {
            // 长按已被「搬运 / 导出」操作单占用，多选入口只能放在列表顶部
            item(key = "skills_batch_entry", contentType = "batch_entry") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { selection.enterSelectionMode() }) {
                        Icon(
                            imageVector = FeatherIcons.CheckSquare,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.provider_models_select))
                    }
                }
            }
        }
        groups.forEach { group ->
            val groupKey = group.label?.let { "tag:$it" } ?: "untagged"
            val expanded = groupKey !in collapsedTags
            item(key = "header:$groupKey", contentType = "group_header") {
                CollapsibleGroupHeader(
                    text = group.label ?: stringResource(R.string.skills_tags_ungrouped),
                    expanded = expanded,
                    onToggle = { collapsedTags = if (expanded) collapsedTags + groupKey else collapsedTags - groupKey },
                    modifier = Modifier.padding(bottom = Spacing.sm)
                )
            }
            if (expanded) {
                itemsIndexed(items = group.entries,
                    key = { _, entry -> "${groupKey.length}:$groupKey:${skillSelectionId(entry)}" },
                    contentType = { _, _ -> "skill" }
                ) { index, entry ->
                    LazyGroupItem(index = index, count = group.entries.size,
                        modifier = Modifier.animateItem().padding(bottom = if (index == group.entries.lastIndex) Spacing.sm else 0.dp)
                    ) {
                        if (index > 0) SettingsDivider()
                        SkillRow(entry = entry, onDelete = if (entry.builtin) null else ({ onDelete(entry) }),
                            onLongClick = { actionEntry = entry }, onClick = { onOpenDetail(entry) },
                            selectionMode = selection.selectionMode,
                            selected = selection.isSelected(skillSelectionId(entry)),
                            onToggleSelect = { selection.toggle(skillSelectionId(entry)) })
                    }
                }
            }
        }
    }

    if (showBatchDelete) {
        val selectedEntries = entries.filter { skillSelectionId(it) in selection.selectedIds }.distinctBy(::skillSelectionId)
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.common_delete),
            message = stringResource(R.string.common_batch_delete_confirm, selectedEntries.size),
            onConfirm = {
                showBatchDelete = false
                selection.exit()
                // 删除接口按作用域定位目录，跨作用域的选中必须分组提交
                selectedEntries.groupBy { it.scope }.forEach { (scope, group) ->
                    settingsViewModel.deleteSkills(group.map { it.name }, scope)
                }
            },
            onDismiss = { showBatchDelete = false }
        )
    }

    actionEntry?.let { entry ->
        SkillRowActionsSheet(
            entry = entry,
            onMove = { target ->
                actionEntry = null
                onMove(entry.name, entry.scope, target)
            },
            onExport = {
                actionEntry = null
                onExport(entry)
            },
            onDismiss = { actionEntry = null }
        )
    }
}

/**
 * 技能行：图标 + 名称/描述 + 右箭头。[onDelete] 为 null 表示不提供删除操作，
 * 此时连左滑手势都不接——免得滑出个按钮让人以为能删；长按仍可用（弹操作单，只给导出）。
 *
 * 多选态下不可删的行也不接点击：叠在整页多选里再去开详情页只会让人失去选中上下文。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkillRow(
    entry: SkillUiEntry,
    onDelete: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    if (onDelete == null) {
        SkillRowContent(
            entry = entry,
            modifier = when {
                selectionMode -> Modifier
                onLongClick != null -> Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else -> Modifier.clickable { onClick() }
            }
        )
        return
    }
    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick,
        onLongClick = onLongClick,
        selectionMode = selectionMode,
        selected = selected,
        onToggleSelect = onToggleSelect
    ) {
        SkillRowContent(entry, Modifier)
    }
}

/** 选中 id：「作用域:名称」——同名技能可能项目与全局各一份。 */

/**
 * 长按技能行的操作单：搬动（全局 ⇄ 当前项目）与导出。
 * 删除仍走左滑那条路，不在这里重复；内置技能随 App 打包，既不能搬也不能删，只剩导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillRowActionsSheet(
    entry: SkillUiEntry,
    onMove: (SkillScope) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = com.aharou.core.translation.rememberTranslatedText(entry.name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.xs)
            )
            SettingsGroup {
                if (!entry.builtin) {
                    SettingsRow(
                        icon = if (entry.scope == SkillScope.GLOBAL) FeatherIcons.Folder else FeatherIcons.Globe,
                        title = stringResource(
                            if (entry.scope == SkillScope.GLOBAL) R.string.skills_move_to_project
                            else R.string.skills_move_to_global
                        ),
                        subtitle = stringResource(
                            if (entry.scope == SkillScope.GLOBAL) R.string.skills_move_to_project_desc
                            else R.string.skills_move_to_global_desc
                        ),
                        onClick = {
                            onMove(
                                if (entry.scope == SkillScope.GLOBAL) SkillScope.PROJECT
                                else SkillScope.GLOBAL
                            )
                        }
                    )
                    SettingsDivider()
                }
                SettingsRow(
                    icon = FeatherIcons.Archive,
                    title = stringResource(R.string.skills_export),
                    subtitle = stringResource(R.string.skills_export_desc),
                    onClick = onExport
                )
            }
        }
    }
}

/** 技能行内容（不含左滑容器与点击处理）。 */
@Composable
private fun SkillRowContent(
    entry: SkillUiEntry,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.semanticColors.cardSurface)
            .padding(start = Spacing.lg, end = Spacing.xs, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FeatherIcons.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = com.aharou.core.translation.rememberTranslatedText(entry.name),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.builtin) {
                    McpPill(
                        text = stringResource(R.string.skills_builtin),
                        textColor = MaterialTheme.colorScheme.outline,
                        backgroundColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                    )
                } else {
                    McpPill(
                        text = stringResource(if (entry.disabled) R.string.common_disabled else R.string.common_enabled),
                        textColor = if (entry.disabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        backgroundColor = (if (entry.disabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary).copy(alpha = 0.12f)
                    )
                }
            }
            val scopeLabel = stringResource(when (entry.scope) {
                SkillScope.BUILTIN -> R.string.skills_builtin
                SkillScope.PROJECT -> R.string.skills_scope_project
                SkillScope.GLOBAL -> R.string.skills_scope_global
            })
            val metadata = listOf(scopeLabel) + entry.classificationLabels + entry.tags
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = com.aharou.core.translation.rememberTranslatedText(entry.description.ifBlank { stringResource(R.string.mcp_no_description) }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 分组内空状态：一行灰字，与行内容对齐。 */
@Composable
private fun SkillEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}
