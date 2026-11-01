package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.settings.presentation.TeamSettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Users

/**
 * 设置 → 团队：入口只有两条（创建团队 / 创建成员）+ 已创建团队列表。
 *
 * 团队 = 成员名单 + 管理员组 + 流程（阶段与驳回边）+ 公约。成员只是名单，
 * 没被任何团队引用的成员不会进主代理的视野，所以「建人」和「建队」是两件分开的事。
 *
 * 表单保存后写回 teams 目录下的 .md，与手写完全等价；文件路径在详情里给出，方便直接改文本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentTeamsSection(
    /** 已有成员数量：只用于「创建成员」这行的摘要。 */
    expertCount: Int = 0,
    /** 打开成员管理页（创建/编辑/删除成员）。 */
    onOpenExperts: () -> Unit = {},
    viewModel: TeamSettingsViewModel = hiltViewModel()
) {
    val teams by viewModel.teams.collectAsStateWithLifecycle()
    val availableMembers by viewModel.members.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<AgentTeamEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<AgentTeamEntry?>(null) }
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Plus,
                    title = stringResource(R.string.team_new),
                    onClick = {
                        viewModel.resetSaveState()
                        editorTarget = EditorTarget(null)
                    }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Users,
                    title = stringResource(R.string.team_create_member),
                    subtitle = stringResource(R.string.settings_team_experts_desc),
                    trailing = {
                        Text(
                            text = expertCount.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = onOpenExperts
                )
            }
        }

        if (teams.isEmpty()) {
            Text(
                text = stringResource(R.string.team_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xl)
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AgentDefinitionScope.entries.forEach { scope ->
                    val scoped = teams.filter { it.scope == scope }
                    if (scoped.isEmpty()) return@forEach
                    SettingsGroupHeader(
                        text = stringResource(
                            if (scope == AgentDefinitionScope.GLOBAL) R.string.team_scope_global
                            else R.string.team_scope_project
                        )
                    )
                    SettingsGroup {
                        scoped.forEachIndexed { index, entry ->
                            if (index > 0) SettingsDivider()
                            SwipeToDeleteRow(
                                onDelete = { pendingDelete = entry },
                                onClick = { detail = entry }
                            ) {
                                TeamRow(entry)
                            }
                        }
                    }
                }
            }
        }
    }

    detail?.let { entry ->
        TeamDetailSheet(
            entry = entry,
            onEdit = {
                viewModel.resetSaveState()
                editorTarget = EditorTarget(entry)
            },
            onDismiss = { detail = null }
        )
    }

    editorTarget?.let { target ->
        TeamEditorSheet(
            initial = target.entry,
            availableMembers = availableMembers,
            saveState = saveState,
            onSave = { form, originalName -> viewModel.save(form, originalName) },
            onSaved = {
                editorTarget = null
                viewModel.resetSaveState()
            },
            onDismiss = {
                editorTarget = null
                viewModel.resetSaveState()
            }
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.team_delete_title)) },
            text = { Text(stringResource(R.string.team_delete_message, entry.team.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(entry)
                        pendingDelete = null
                    }
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 编辑目标：新建时 entry 为 null；编辑时带上原条目，保存后按原名改文件。 */
private data class EditorTarget(val entry: AgentTeamEntry?)

/** 单个团队行：图标 + 名称/规模摘要 + 右箭头。 */
@Composable
private fun TeamRow(entry: AgentTeamEntry) {
    val team = entry.team
    Row(
        modifier = Modifier
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
                imageVector = FeatherIcons.Users,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = team.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.team_members_summary,
                    team.members.size,
                    team.admins.size,
                    team.stages.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 团队详情：名单按层级分组、流程按阶段列出驳回边、公约与文件路径。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamDetailSheet(
    entry: AgentTeamEntry,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    val team = entry.team
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = team.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (team.description.isNotBlank()) {
                Text(
                    text = team.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (team.admins.isNotEmpty()) {
                SettingsGroupHeader(text = stringResource(R.string.team_detail_admins))
                Text(
                    text = team.admins.joinToString("、"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (team.members.isNotEmpty()) {
                SettingsGroupHeader(text = stringResource(R.string.team_detail_members))
                team.levels.forEach { level ->
                    val names = team.members.filter { it.level == level }.map { it.name }
                    if (names.isEmpty()) return@forEach
                    Text(
                        text = teamRoleLabel(level) + "：" + names.joinToString("、"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.team_detail_process))
            team.stages.forEachIndexed { index, stage ->
                val members = stage.tasks.mapNotNull { it.agent }.joinToString("、")
                Text(
                    text = buildString {
                        append(index + 1).append(". ").append(stage.title)
                        append("（").append(teamRoleLabel(stage.level)).append("）")
                        if (members.isNotEmpty()) append("：").append(members)
                        if (stage.isReview) append(" · ").append(stringResource(R.string.team_editor_stage_review))
                        stage.rejectTo?.let {
                            append("\n   ").append(stringResource(R.string.team_reject_to, it))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (team.charter.isNotBlank()) {
                SettingsGroupHeader(text = stringResource(R.string.team_detail_charter))
                Text(
                    text = team.charter,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.team_detail_file))
            Text(
                text = team.filePath ?: "-",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.team_run_hint, team.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.team_editor_edit))
            }
        }
    }


}

@Composable
private fun teamRoleLabel(level: Int): String = stringResource(R.string.team_level_label, level)
