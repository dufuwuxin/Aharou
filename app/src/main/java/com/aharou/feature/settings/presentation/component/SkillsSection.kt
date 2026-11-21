package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
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
import com.aharou.feature.settings.presentation.filterSkillLibrary
import com.aharou.feature.settings.presentation.toggleSkillType
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

/** Installed skills share one tag/source/search filter and retain their scoped action callbacks. */
@Composable
internal fun SkillsSection(
    projectName: String?,
    entries: List<SkillUiEntry>,
    onDelete: (SkillUiEntry) -> Unit,
    onMove: (String, SkillScope, SkillScope) -> Unit,
    onExport: (SkillUiEntry) -> Unit,
    onOpenDetail: (SkillUiEntry) -> Unit
) {
    val settingsViewModel = rememberSettingsViewModel()
    SkillsLibraryContent(
        entries = entries,
        onDelete = onDelete,
        onDeleteBatch = { selected ->
            selected.groupBy { it.scope }.forEach { (scope, group) ->
                settingsViewModel.deleteSkills(group.map { it.name }, scope)
            }
        },
        onMove = onMove,
        onExport = onExport,
        onOpenDetail = onOpenDetail,
        typeManager = { SkillTypeManager() }
    )
}

@Composable
internal fun SkillsLibraryContent(
    entries: List<SkillUiEntry>,
    onDelete: (SkillUiEntry) -> Unit,
    onDeleteBatch: (List<SkillUiEntry>) -> Unit,
    onMove: (String, SkillScope, SkillScope) -> Unit,
    onExport: (SkillUiEntry) -> Unit,
    onOpenDetail: (SkillUiEntry) -> Unit,
    typeManager: @Composable () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var scopeFilter by rememberSaveable { mutableStateOf<SkillScope?>(null) }
    var selectedTagIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val groups = remember(entries) { groupSkillsByTag(entries) }
    val filtered = remember(entries, query, scopeFilter, selectedTagIds) {
        filterSkillLibrary(entries, scopeFilter, selectedTagIds).filter { it.matchesSkillQuery(query) }
    }
    val selection = rememberListSelectionState()
    val selectionIds = filtered.filterNot { it.builtin }.map(::skillSelectionId)
    LaunchedEffect(selectionIds) { selection.retain(selectionIds) }
    var showBatchDelete by remember { mutableStateOf(false) }
    var actionEntry by remember { mutableStateOf<SkillUiEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f).testTag("skills_tag_bar"),
                contentPadding = PaddingValues(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                item(key = "all") {
                    FilterChip(
                        selected = selectedTagIds.isEmpty(),
                        onClick = { selectedTagIds = emptyList() },
                        label = { Text(stringResource(R.string.skills_market_category_all)) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("skills_tags_all")
                    )
                }
                items(groups, key = { it.filterId }) { group ->
                    FilterChip(
                        selected = group.filterId in selectedTagIds,
                        onClick = { selectedTagIds = toggleSkillType(selectedTagIds, group.filterId) },
                        label = {
                            Text(group.label ?: stringResource(R.string.skills_tags_ungrouped), maxLines = 1)
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("skills_tag:${group.filterId}")
                    )
                }
            }
            typeManager()
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            AppTextField(
                value = query, onValueChange = { query = it },
                placeholder = stringResource(R.string.skills_search_hint),
                modifier = Modifier.weight(1f).testTag("skills_search"), singleLine = true
            )
            IconButton(
                onClick = { if (selection.selectionMode) selection.exit() else selection.enterSelectionMode() },
                enabled = selection.selectionMode || selectionIds.isNotEmpty(),
                modifier = Modifier.size(48.dp).testTag("skills_select")
            ) {
                Icon(
                    FeatherIcons.CheckSquare,
                    contentDescription = stringResource(
                        if (selection.selectionMode) R.string.common_cancel else R.string.provider_models_select
                    ),
                    tint = if (selection.selectionMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("skills_scope_bar"),
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item(key = "all") {
                FilterChip(
                    selected = scopeFilter == null, onClick = { scopeFilter = null },
                    label = { Text(stringResource(R.string.skills_scope_all)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("skills_scope:all")
                )
            }
            items(SkillScope.entries, key = { it.name }) { scope ->
                FilterChip(
                    selected = scopeFilter == scope, onClick = { scopeFilter = scope },
                    label = {
                        Text(stringResource(when (scope) {
                            SkillScope.BUILTIN -> R.string.skills_builtin
                            SkillScope.PROJECT -> R.string.skills_scope_project
                            SkillScope.GLOBAL -> R.string.skills_scope_global
                        }), maxLines = 1)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("skills_scope:${scope.name}")
                )
            }
        }
        if (selection.selectionMode) {
            SelectionActionBar(
                selectedCount = selection.selectedCount,
                allSelected = selection.isAllSelected(selectionIds),
                onToggleSelectAll = { selection.toggleSelectAll(selectionIds) },
                onExit = { selection.exit() },
                modifier = Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.sm)
                    .testTag("skills_selection_bar"),
                actions = listOf(SelectionAction(
                    icon = FeatherIcons.Trash2,
                    label = stringResource(R.string.common_delete),
                    onClick = { showBatchDelete = true },
                    destructive = true
                ))
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                entries.isNotEmpty() && filtered.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.skills_search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> SkillsListContent(
                    entries = filtered, onDelete = onDelete,
                    onLongClick = { actionEntry = it }, onOpenDetail = onOpenDetail,
                    selectionMode = selection.selectionMode,
                    selectedIds = selection.selectedIds,
                    onToggleSelect = { selection.toggle(skillSelectionId(it)) }
                )
            }
        }
    }

    if (showBatchDelete) {
        val selectedEntries = filtered.filter { !it.builtin && skillSelectionId(it) in selection.selectedIds }
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.common_delete),
            message = stringResource(R.string.common_batch_delete_confirm, selectedEntries.size),
            onConfirm = {
                showBatchDelete = false
                selection.exit()
                onDeleteBatch(selectedEntries)
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
            onExport = { actionEntry = null; onExport(entry) },
            onDismiss = { actionEntry = null }
        )
    }
}

@Composable
private fun SkillsListContent(
    entries: List<SkillUiEntry>,
    onDelete: (SkillUiEntry) -> Unit,
    onLongClick: (SkillUiEntry) -> Unit,
    onOpenDetail: (SkillUiEntry) -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelect: (SkillUiEntry) -> Unit
) {
    if (entries.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.xl, vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Box(
                    modifier = Modifier.size(64.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.lg)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(FeatherIcons.Book, contentDescription = null, modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.skills_empty), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.skills_empty_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        return
    }
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lg).testTag("skills_list"),
        contentPadding = PaddingValues(bottom = Spacing.xl)
    ) {
        itemsIndexed(entries, key = { _, entry -> skillSelectionId(entry) }, contentType = { _, _ -> "skill" }) { index, entry ->
            LazyGroupItem(index = index, count = entries.size,
                modifier = Modifier.animateItem().testTag("skill_row:${skillSelectionId(entry)}")
            ) {
                if (index > 0) SettingsDivider()
                SkillRow(entry = entry, onDelete = if (entry.builtin) null else ({ onDelete(entry) }),
                    onLongClick = { onLongClick(entry) }, onClick = { onOpenDetail(entry) },
                    selectionMode = selectionMode, selected = skillSelectionId(entry) in selectedIds,
                    onToggleSelect = { onToggleSelect(entry) })
            }
        }
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
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
