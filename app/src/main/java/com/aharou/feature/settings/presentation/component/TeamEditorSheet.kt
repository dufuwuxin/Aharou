package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamForm
import com.aharou.feature.agent.domain.subagent.AgentTeamSaveError
import com.aharou.feature.agent.domain.subagent.AgentTeamStageForm
import com.aharou.feature.agent.domain.subagent.TeamMemberRef
import com.aharou.feature.agent.domain.subagent.toForm
import com.aharou.feature.settings.presentation.AgentTeamSaveState

/**
 * 创建 / 编辑团队的表单，四段按用户口径排：团队成员 → 管理员组 → 团队流程 → 团队公约。
 *
 * 成员只是名单：只有写进这张名单的成员才会被主代理看到（见 SystemPromptProvider 的成员索引过滤）。
 * 流程按阶段排，关卡（审核）上可以选「驳回回退到哪一段」，这就是关系链里的「驳回」。
 *
 * @param initial 编辑目标；null 表示新建。
 * @param availableMembers 现有全部成员名：建团队时从这里挑人，挑不到就先去创建成员。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TeamEditorSheet(
    initial: AgentTeamEntry?,
    availableMembers: List<String>,
    saveState: AgentTeamSaveState,
    onSave: (AgentTeamForm, String?) -> Unit,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial?.team?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initial?.team?.description.orEmpty()) }
    var charter by rememberSaveable { mutableStateOf(initial?.team?.charter.orEmpty()) }
    var scopeIsGlobal by rememberSaveable {
        mutableStateOf(initial?.scope?.let { it == AgentDefinitionScope.GLOBAL } ?: true)
    }
    var members by remember(initial) {
        mutableStateOf(initial?.let { entry -> entry.team.toForm(entry.scope).members } ?: emptyList())
    }
    var admins by remember(initial) {
        mutableStateOf(initial?.let { entry -> entry.team.toForm(entry.scope).admins } ?: emptyList())
    }
    var stages by remember(initial) {
        mutableStateOf(
            initial?.let { entry -> entry.team.toForm(entry.scope).stages }
                ?: listOf(AgentTeamStageForm())
        )
    }

    LaunchedEffect(saveState) {
        if (saveState is AgentTeamSaveState.Saved) onSaved()
    }

    // 名单里没有的成员不可能是管理员：两边用一个真源，避免出现「管理员不在名单」的保存失败。
    val rows = remember(members, availableMembers) {
        (members.map { it.name } + availableMembers).distinct()
    }
    val canSave = name.isNotBlank() && stages.any { it.members.isNotEmpty() || it.prompt.isNotBlank() }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(
                    if (initial == null) R.string.team_editor_title_new else R.string.team_editor_title_edit
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            (saveState as? AgentTeamSaveState.Failed)?.let { failed ->
                Text(
                    text = stringResource(failed.error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 1. 基本信息
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.team_editor_name),
                singleLine = true
            )
            AppTextField(
                value = description,
                onValueChange = { description = it },
                label = stringResource(R.string.team_editor_description),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
            ScopeSelector(scopeIsGlobal = scopeIsGlobal, enabled = initial == null) { scopeIsGlobal = it }

            // 2. 成员名单：只有在这里的成员会被主代理读到
            SettingsGroupHeader(text = stringResource(R.string.team_editor_members))
            Text(
                text = stringResource(R.string.team_editor_members_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (availableMembers.isEmpty()) {
                Text(
                    text = stringResource(R.string.team_editor_members_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    availableMembers.forEach { memberName ->
                        val picked = members.any { it.name == memberName }
                        FilterChip(
                            selected = picked,
                            onClick = {
                                members = if (picked) {
                                    members.filterNot { it.name == memberName }
                                } else {
                                    members + TeamMemberRef(memberName, 1)
                                }
                                // 移出名单的人不能再当管理员
                                if (picked) admins = admins.filterNot { it == memberName }
                            },
                            label = { Text(memberName) }
                        )
                    }
                }
            }
            // 每个已选成员的层级：一级在前、层级越高越靠执行
            members.forEach { ref ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = ref.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    LevelStepper(
                        level = ref.level,
                        onChange = { level ->
                            members = members.map { if (it.name == ref.name) it.copy(level = level) else it }
                        }
                    )
                }
            }

            // 3. 管理员组：可多个，多模型并行出判断，主代理汇总成策略建议
            SettingsGroupHeader(text = stringResource(R.string.team_editor_admins))
            Text(
                text = stringResource(R.string.team_editor_admins_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                members.forEach { ref ->
                    val picked = admins.contains(ref.name)
                    FilterChip(
                        selected = picked,
                        onClick = {
                            admins = if (picked) admins - ref.name else admins + ref.name
                        },
                        label = { Text(ref.name) }
                    )
                }
            }

            // 4. 团队流程：阶段 + 关卡 + 驳回回边
            SettingsGroupHeader(text = stringResource(R.string.team_editor_process))
            Text(
                text = stringResource(R.string.team_editor_process_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { stages = standardFiveStages(members) }) {
                Text(stringResource(R.string.team_editor_preset_standard))
            }

            stages.forEachIndexed { index, stage ->
                StageEditor(
                    index = index,
                    stage = stage,
                    members = rows,
                    earlierTitles = stages.take(index).map { it.title.trim() }.filter { it.isNotEmpty() },
                    onChange = { updated -> stages = stages.toMutableList().also { it[index] = updated } },
                    onRemove = { stages = stages.filterIndexed { i, _ -> i != index } },
                    canRemove = stages.size > 1
                )
            }
            TextButton(onClick = {
                stages = stages + AgentTeamStageForm(
                    title = "",
                    level = stages.size + 1,
                    members = emptyList()
                )
            }) {
                Text(stringResource(R.string.team_editor_add_stage))
            }

            // 5. 团队公约：团队的用途介绍，也是每个成员指令前的共同约束
            SettingsGroupHeader(text = stringResource(R.string.team_editor_charter))
            Text(
                text = stringResource(R.string.team_editor_charter_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppTextField(
                value = charter,
                onValueChange = { charter = it },
                label = stringResource(R.string.team_editor_charter),
                singleLine = false,
                minLines = 3,
                maxLines = 8
            )

            Button(
                onClick = {
                    onSave(
                        AgentTeamForm(
                            name = name,
                            description = description,
                            scope = if (scopeIsGlobal) AgentDefinitionScope.GLOBAL else AgentDefinitionScope.PROJECT,
                            charter = charter,
                            admins = admins,
                            members = members,
                            stages = stages
                        ),
                        initial?.team?.name
                    )
                },
                enabled = canSave && saveState !is AgentTeamSaveState.Saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (saveState is AgentTeamSaveState.Saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }
}

/** 一个阶段的编辑块：标题 / 层级 / 成员 / 指令 / 关卡 / 驳回目标。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageEditor(
    index: Int,
    stage: AgentTeamStageForm,
    members: List<String>,
    earlierTitles: List<String>,
    onChange: (AgentTeamStageForm) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.team_editor_stage_title_hint, index + 1),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (canRemove) {
                TextButton(onClick = onRemove) {
                    Text(
                        text = stringResource(R.string.team_editor_remove_stage),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        AppTextField(
            value = stage.title,
            onValueChange = { onChange(stage.copy(title = it)) },
            label = stringResource(R.string.team_editor_stage_title),
            singleLine = true
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.team_editor_stage_level),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            LevelStepper(level = stage.level, onChange = { onChange(stage.copy(level = it)) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            members.forEach { memberName ->
                val picked = stage.members.contains(memberName)
                FilterChip(
                    selected = picked,
                    onClick = {
                        val next = if (picked) stage.members - memberName else stage.members + memberName
                        onChange(stage.copy(members = next))
                    },
                    label = { Text(memberName) }
                )
            }
        }
        AppTextField(
            value = stage.prompt,
            onValueChange = { onChange(stage.copy(prompt = it)) },
            label = stringResource(R.string.team_editor_stage_prompt),
            singleLine = false,
            minLines = 2,
            maxLines = 5
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = stage.isReview, onCheckedChange = { onChange(stage.copy(isReview = it)) })
            Text(
                text = stringResource(R.string.team_editor_stage_review),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (stage.isReview) {
            Text(
                text = stringResource(R.string.team_editor_stage_reject),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FilterChip(
                    selected = stage.rejectTo.isBlank(),
                    onClick = { onChange(stage.copy(rejectTo = "")) },
                    label = { Text(stringResource(R.string.team_editor_stage_reject_none)) }
                )
                earlierTitles.forEach { title ->
                    FilterChip(
                        selected = stage.rejectTo == title,
                        onClick = { onChange(stage.copy(rejectTo = title)) },
                        label = { Text(title) }
                    )
                }
            }
        }
    }
}

/** 层级步进器：1~5 级够表达「一级成员组 / 二级 / 三级」这类结构，再多就该拆团队了。 */
@Composable
private fun LevelStepper(level: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange((level - 1).coerceAtLeast(1)) }, enabled = level > 1) {
            Text("−")
        }
        Text(
            text = stringResource(R.string.team_level_label, level),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        TextButton(onClick = { onChange((level + 1).coerceAtMost(5)) }, enabled = level < 5) {
            Text("+")
        }
    }
}

/** 作用域选择：文件写在哪个目录决定它是全局团队还是项目团队，编辑时不允许改。 */
@Composable
private fun ScopeSelector(scopeIsGlobal: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FilterChip(
            selected = scopeIsGlobal,
            onClick = { if (enabled) onChange(true) },
            enabled = enabled,
            label = { Text(stringResource(R.string.team_scope_global)) }
        )
        FilterChip(
            selected = !scopeIsGlobal,
            onClick = { if (enabled) onChange(false) },
            enabled = enabled,
            label = { Text(stringResource(R.string.team_scope_project)) }
        )
    }
}

/** 标准五步：拆解 → 设计 → 分配 → 执行 → 审核（审核关驳回回第二步）。 */
private fun standardFiveStages(members: List<TeamMemberRef>): List<AgentTeamStageForm> {
    val levelOne = members.filter { it.level == 1 }.map { it.name }
    val levelTwo = members.filter { it.level >= 2 }.map { it.name }
    return listOf(
        AgentTeamStageForm(title = "问题拆解", level = 1, members = levelOne, prompt = "把目标拆成可独立验收的子问题，列出边界与验收标准。"),
        AgentTeamStageForm(title = "方案设计", level = 1, members = levelOne, prompt = "针对拆解结果给出方案，标明关键取舍与风险。"),
        AgentTeamStageForm(title = "任务分配", level = 1, members = emptyList(), prompt = "把方案落成可执行的分配表：谁做、做什么、交付什么。"),
        AgentTeamStageForm(title = "成员执行", level = 2, members = levelTwo.ifEmpty { levelOne }, prompt = "按分配表完成你负责的部分，交回产物与自测结论。"),
        AgentTeamStageForm(
            title = "任务审核",
            level = 1,
            members = emptyList(),
            prompt = "逐条核对验收标准；不过关就写明必改点。",
            isReview = true,
            rejectTo = "成员执行"
        )
    )
}

/** 新建时给个像样的默认阶段名；放在这里而不是字符串表里，是为了避免为一条占位文案加两处翻译。 */

private fun AgentTeamSaveError.messageRes(): Int = when (this) {
    AgentTeamSaveError.INVALID_NAME -> R.string.team_editor_error_invalid_name
    AgentTeamSaveError.EMPTY_STAGES -> R.string.team_editor_error_empty_stages
    AgentTeamSaveError.INCOMPLETE_TASK -> R.string.team_editor_error_incomplete_task
    AgentTeamSaveError.NAME_CONFLICT -> R.string.team_editor_error_name_conflict
    AgentTeamSaveError.UNKNOWN_MEMBER -> R.string.team_editor_error_unknown_member
    AgentTeamSaveError.ADMIN_NOT_MEMBER -> R.string.team_editor_error_admin_not_member
    AgentTeamSaveError.BAD_REJECT_TARGET -> R.string.team_editor_error_bad_reject
    AgentTeamSaveError.IO_FAILED -> R.string.team_editor_error_io
}
