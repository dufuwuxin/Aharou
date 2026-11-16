package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.aharou.core.theme.Radius
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamForm
import com.aharou.feature.agent.domain.subagent.AgentTeamSaveError
import com.aharou.feature.agent.domain.subagent.AgentTeamStageForm
import com.aharou.feature.agent.domain.subagent.TeamMemberRef
import com.aharou.feature.agent.domain.subagent.TeamFlowNode
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.TeamStage
import com.aharou.feature.agent.domain.subagent.TeamTask
import com.aharou.feature.agent.domain.subagent.TeamFlowGraph
import com.aharou.feature.agent.domain.subagent.withFlowTasks
import com.aharou.feature.agent.domain.subagent.toForm
import com.aharou.feature.settings.presentation.AgentTeamSaveState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.ChevronDown

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

    var routes by remember(initial) { mutableStateOf(initial?.team?.routes.orEmpty()) }
    var routesRemoved by remember(initial) { mutableStateOf(false) }

    fun updateStages(updated: List<AgentTeamStageForm>) {
        val previousStages = stages
        stages = updated.mapIndexed { index, stage ->
            if (stage.rejectTo.isNotBlank() && updated.take(index).none { it.title.trim().equals(stage.rejectTo.trim(), ignoreCase = true) }) {
                stage.copy(rejectTo = "")
            } else stage
        }
        val nodes = stages.flatMap { stage -> stage.tasks.filter { it.prompt.isNotBlank() }.map { TeamFlowNode(stage.id, it.id) } }.toSet()
        stages = removeDeletedTeamNodeReferences(previousStages, stages)
        val kept = routes.filter { it.from in nodes && it.to in nodes }
        if (kept.size != routes.size) routesRemoved = true
        routes = kept
    }

    LaunchedEffect(saveState) {
        if (saveState is AgentTeamSaveState.Saved) onSaved()
    }

    // 阶段里可点选的成员：只列团队自己的成员（不是 all availableMembers），避免还没添加的人被选中。
    val stageMembers = remember(members) { members.map { it.name } }
    val flowError = TeamFlowGraph.validate(AgentTeam(
        name = name,
        description = description,
        charter = charter,
        admins = admins,
        members = members,
        stages = stages.map { stage -> TeamStage(
            title = stage.title,
            tasks = stage.tasks.filter { it.prompt.isNotBlank() }.map { TeamTask(agent = it.agent, prompt = it.prompt, id = it.id) },
            id = stage.id,
            isReview = stage.isReview
        ) },
        routes = routes
    ))
    val canSave = flowError == null && name.isNotBlank() && stages.any { stage ->
        stage.members.isNotEmpty() || stage.prompt.isNotBlank() || stage.tasks.any { task ->
            task.prompt.isNotBlank() && (task.agent.isNotBlank() || task.department.isNotBlank() || task.agentQuery.isNotBlank())
        }
    }

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

            var showPicker by remember { mutableStateOf(false) }
            SettingsGroupHeader(text = stringResource(R.string.team_editor_members))
            if (availableMembers.isEmpty() && members.isEmpty()) {
                Text(
                    text = stringResource(R.string.team_editor_members_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // 已选成员以卡片行展示；右侧点选择器挑人，而不是把全量名单一次性摊开
                if (members.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        members.forEach { member ->
                            FilterChip(selected = true, onClick = {
                                members = members.filterNot { it.name == member.name }
                                admins = admins.filterNot { it == member.name }
                                updateStages(stages.map { stage -> stage.copy(
                                    members = stage.members.filterNot { it.equals(member.name, ignoreCase = true) },
                                    tasks = stage.tasks.filterNot { it.agent.equals(member.name, ignoreCase = true) },
                                    interfaces = stage.interfaces.filterNot { it.from.equals(member.name, ignoreCase = true) }
                                ) })
                            }, label = { Text(member.name) }, trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.common_remove), Modifier.size(16.dp)) },
                                modifier = Modifier.testTag("team-member-remove-${member.name}"))
                        }
                    }
                }
                // 选择器按钮：打开二级勾选页，把已选的人加进来
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showPicker = true }) {
                        Icon(
                            imageVector = FeatherIcons.Plus,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(stringResource(R.string.team_editor_add_members))
                    }
                }
            }
            if (showPicker) {
                MemberPickerSheet(
                    available = availableMembers.filter { name -> members.all { it.name != name } },
                    selected = emptyList(),
                    onConfirm = { picked ->
                        val existingNames = members.map { it.name }.toSet()
                        val newRefs = picked.filter { it !in existingNames }
                            .map { name -> TeamMemberRef(name, 1) }
                        members = (members + newRefs).distinctBy { it.name }
                        showPicker = false
                    },
                    onDismiss = { showPicker = false }
                )
            }

            // Selecting an available administrator also adds them to the team roster.
            SettingsGroupHeader(text = stringResource(R.string.team_editor_admins))
            Text(
                text = stringResource(R.string.team_editor_admin_select_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val adminCandidates = (listOf(com.aharou.feature.agent.domain.subagent.MAIN_AGENT_ADMIN) + members.map { it.name } + availableMembers).distinct()
            if (adminCandidates.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    adminCandidates.forEach { memberName ->
                        val picked = admins.contains(memberName)
                        FilterChip(
                            selected = picked,
                            onClick = {
                                if (memberName != com.aharou.feature.agent.domain.subagent.MAIN_AGENT_ADMIN && members.none { it.name == memberName }) members = members + TeamMemberRef(memberName)
                                admins = listOf(memberName)
                            },
                            label = { Text(if (memberName == com.aharou.feature.agent.domain.subagent.MAIN_AGENT_ADMIN) stringResource(R.string.team_main_agent) else memberName) },
                            modifier = Modifier.testTag("team_admin_$memberName")
                        )
                    }
                }
            }
            SettingsGroupHeader(text = stringResource(R.string.team_relationships))
            var relationshipDialog by remember { mutableStateOf(false) }
            var selectedNodes by remember { mutableStateOf(emptySet<TeamFlowNode>()) }
            val visibleRoutes = remember(stages, routes) { routes.ifEmpty { teamDefaultRoutes(stages) } }
            TeamFlowMap(stages, visibleRoutes, selectedNodes) { node ->
                if (node in selectedNodes) selectedNodes = selectedNodes - node
                else {
                    val hadSource = selectedNodes.isNotEmpty()
                    selectedNodes = selectedNodes + node
                    if (hadSource) relationshipDialog = true
                }
            }
            TextButton(onClick = { relationshipDialog = true }, modifier = Modifier.fillMaxWidth().testTag("team_relationship_edit")) {
                Text(stringResource(R.string.team_relationship_edit))
            }
            if (relationshipDialog) TeamRelationshipDialog(stages, routes, selectedNodes, onDismiss = {
                relationshipDialog = false
                selectedNodes = emptySet()
            },
                onCollaboration = { sources, targets, both, about, remove ->
                    stages = editTeamCollaboration(stages, sources, targets, both, about, remove)
                    selectedNodes = emptySet()
                },
                onRoute = { sources, targets, action, remove ->
                    val updated = editTeamRoutes(stages, routes, sources, targets, action, remove)
                    if (updated == null) false else { routes = updated; selectedNodes = emptySet(); true }
                })

            var stageSettingsExpanded by remember { mutableStateOf(false) }
            TextButton(onClick = { stageSettingsExpanded = !stageSettingsExpanded }, modifier = Modifier.testTag("team_stage_settings")) {
                Text(stringResource(R.string.team_editor_process))
            }
            if (stageSettingsExpanded) {
            Text(
                text = stringResource(R.string.team_editor_process_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { stages = standardFiveStages(members).map { it.withFlowTasks() }; routes = emptyList() }) {
                Text(stringResource(R.string.team_editor_preset_standard))
            }

            // 流程图：节点按顺序竖向排列，连线走左侧，驳回边用虚线标出
            stages.forEachIndexed { index, stage ->
                var expanded by remember(stage.id) { mutableStateOf(false) }
                ProcessNodeCard(
                    index = index + 1,
                    stage = stage,
                    roleLabel = stageRoleLabel(index + 1),
                    isReview = stage.isReview,
                    rejectToTitle = stage.rejectTo,
                    rejectTargets = stages.take(index).map { it.title.trim() }.filter { it.isNotEmpty() },
                    expanded = expanded,
                    onToggleExpand = { expanded = it },
                    onChange = { updated ->
                        val added = updated.tasks.map { it.agent }.filter { candidate ->
                            candidate in availableMembers &&
                                candidate != com.aharou.feature.agent.domain.subagent.MAIN_AGENT_ADMIN &&
                                members.none { it.name.equals(candidate, true) }
                        }.distinct().map { TeamMemberRef(it) }
                        members = members + added
                        updateStages(stages.mapIndexed { i, current ->
                            when {
                                i == index -> updated
                                stage.title.isNotBlank() && current.rejectTo.trim().equals(stage.title.trim(), ignoreCase = true) -> current.copy(rejectTo = updated.title.trim())
                                else -> current
                            }
                        })
                    },
                    onRemove = { updateStages(stages.filterIndexed { i, _ -> i != index }) },
                    canRemove = stages.size > 1,
                    members = stageMembers,
                    availableMembers = availableMembers
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

            }
            var routesExpanded by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { routesExpanded = !routesExpanded }, modifier = Modifier.testTag("team_routes_advanced")) {
                Text(stringResource(R.string.team_routes_advanced))
            }
            if (routesExpanded) TeamRouteEditor(stages, routes) { routes = it }
            if (routesRemoved) {
                Text(stringResource(R.string.team_flow_removed_endpoints), color = MaterialTheme.colorScheme.error)
            }
            if (flowError != null) {
                Text(stringResource(R.string.team_flow_invalid), color = MaterialTheme.colorScheme.error)
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
            evidenceRequired = initial?.team?.evidenceRequired ?: false,
                            admins = admins,
                            members = members,
                            stages = stages.map(::taskEditorStageForSave),
                            routes = routes
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

/** 一个阶段的编辑块：标题 / 成员 / 指令 / 关卡 / 驳回目标。
 * 阶段顺序就是列表顺序，不再单独维护“执行层级”——第几阶段由 index 体现。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageEditor(
    index: Int,
    stage: AgentTeamStageForm,
    members: List<String>,
    availableMembers: List<String>,
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
        }
        AppTextField(
            value = stage.title,
            onValueChange = { onChange(stage.copy(title = it)) },
            label = stringResource(R.string.team_editor_stage_title),
            singleLine = true
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            members.forEach { memberName ->
                val picked = stage.members.contains(memberName)
                FilterChip(
                    selected = picked,
                    onClick = {
                        val next = if (picked) stage.members - memberName else stage.members + memberName
                        // 成员出阶段时，以其为来源的协作边一起清掉，免留指向已走人的悬空边；
                        // with 侧有意不清：目标可以是部门/标签，未必是本阶段成员
                        onChange(stage.copy(
                            members = next,
                            tasks = stage.tasks.filterNot { picked && it.agent.equals(memberName, true) }.let { tasks ->
                                if (!picked && stage.prompt.isNotBlank() && tasks.none { it.agent.equals(memberName, true) }) tasks + com.aharou.feature.agent.domain.subagent.AgentTeamTaskForm(agent = memberName, prompt = stage.prompt) else tasks
                            },
                            interfaces = stage.interfaces.filter { edge -> next.any { it.equals(edge.from, ignoreCase = true) } }
                        ))
                    },
                    label = { Text(if (memberName == com.aharou.feature.agent.domain.subagent.MAIN_AGENT_ADMIN) stringResource(R.string.team_main_agent) else memberName) }
                )
            }
        }
        AppTextField(
            value = stage.prompt,
            onValueChange = { onChange(editSharedTeamPrompt(stage, it)) },
            label = stringResource(R.string.team_editor_stage_prompt),
            singleLine = false,
            minLines = 2,
            maxLines = 5
        )
        TeamTaskEditor(tasks = stage.tasks, availableMembers = availableMembers, onChange = { tasks ->
            onChange(stage.copy(tasks = tasks,
                members = tasks.map { it.agent }.filter { it.isNotBlank() }.distinct(),
                interfaces = tasks.flatMap { task -> task.interfaces.map { edge ->
                    com.aharou.feature.agent.domain.subagent.TeamInterfaceEdgeForm(task.agent, edge.with, edge.about)
                } }))
        })
        AppTextField(value = stage.condition, onValueChange = { onChange(stage.copy(condition = it)) },
            label = stringResource(R.string.team_task_condition), singleLine = true)
        Text(stringResource(R.string.team_task_condition_hint), style = MaterialTheme.typography.bodySmall)
        // 对齐只在阶段内选中的成员之间建立；成员勾选产生的空边已在勾选时同步清除
        InterfaceEdgeEditor(stage = stage, members = stage.members, onChange = { changed ->
            val removed = stage.interfaces - changed.interfaces.toSet()
            val added = changed.interfaces - stage.interfaces.toSet()
            onChange(changed.copy(tasks = stage.tasks.map { task ->
                task.copy(interfaces = task.interfaces.filterNot { edge ->
                    removed.any { it.from.equals(task.agent, true) && it.with == edge.with && it.about == edge.about }
                } + added.filter { it.from.equals(task.agent, true) }.map { com.aharou.feature.agent.domain.subagent.TeamInterface(it.with, it.about) })
            }))
        })
        Text(stringResource(R.string.team_communication_title), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            com.aharou.feature.agent.domain.subagent.TeamCommunication.entries.forEach { policy ->
                FilterChip(selected = stage.communication == policy,
                    onClick = { onChange(stage.copy(communication = policy)) },
                    label = { Text(stringResource(when (policy) {
                        com.aharou.feature.agent.domain.subagent.TeamCommunication.SAME_STAGE -> R.string.team_communication_stage
                        com.aharou.feature.agent.domain.subagent.TeamCommunication.PARENT_ONLY -> R.string.team_communication_parent
                        com.aharou.feature.agent.domain.subagent.TeamCommunication.RUN -> R.string.team_communication_run
                    })) })
            }
        }
        Text(stringResource(R.string.team_communication_hint), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.team_message_limit, stage.messageLimit), Modifier.weight(1f))
            TextButton(enabled = stage.messageLimit > 1, onClick = { onChange(stage.copy(messageLimit = stage.messageLimit - 1)) }) { Text("−") }
            TextButton(enabled = stage.messageLimit < 100, onClick = { onChange(stage.copy(messageLimit = stage.messageLimit + 1)) }) { Text("+") }
        }
        AppTextField(value = stage.communicationGuide,
            onValueChange = { onChange(stage.copy(communicationGuide = it)) },
            label = stringResource(R.string.team_communication_guide), singleLine = false, minLines = 2, maxLines = 5)
        Text(text = stringResource(R.string.team_editor_stage_type), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip(
                selected = !stage.isReview,
                onClick = { onChange(stage.copy(isReview = false, rejectTo = "")) },
                label = { Text(stringResource(R.string.team_editor_stage_execution)) }
            )
            FilterChip(
                selected = stage.isReview,
                onClick = { onChange(stage.copy(isReview = true)) },
                label = { Text(stringResource(R.string.team_editor_stage_review)) }
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


/** 二级选择页：列出可用成员供勾选，确认后回写已选集合。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemberPickerSheet(
    available: List<String>,
    selected: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    singleSelection: Boolean = false
) {
    var picked by remember(selected) { mutableStateOf(selected.toMutableSet()) }
    val canConfirm = if (singleSelection) picked.size == 1 else picked.isNotEmpty()

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
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(if (singleSelection) R.string.team_task_role_choose else R.string.team_select_members_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (available.isEmpty()) {
                Text(
                    text = stringResource(R.string.team_select_members_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                available.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (singleSelection) {
                            RadioButton(
                                selected = name in picked,
                                onClick = { picked = mutableSetOf(name) },
                                modifier = Modifier.testTag("team_role_option_$name")
                            )
                        } else {
                            Checkbox(
                                checked = name in picked,
                                onCheckedChange = { checked ->
                                    picked = (if (checked) picked + name else picked - name).toMutableSet()
                                }
                            )
                        }
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Button(
                onClick = { onConfirm(picked.toList()); onDismiss() },
                enabled = canConfirm,
                modifier = Modifier.fillMaxWidth().testTag("team_member_picker_confirm")
            ) {
                Text(
                    text = stringResource(R.string.team_select_members_confirm, picked.size),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_cancel))
            }
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
    AgentTeamSaveError.INVALID_FLOW -> R.string.team_flow_invalid
    AgentTeamSaveError.IO_FAILED -> R.string.team_editor_error_io
}


/** 把阶段层级映射成角色文案。 */
@Composable
private fun stageRoleLabel(stageIndex: Int): String = stringResource(R.string.team_stage_index_label, stageIndex)

/**
 * 团队流程节点卡片：左侧竖向连线 + 右侧摘要；展开后内嵌 StageEditor 继续编辑。
 * 驳回边（审核关）用红色标签标注打回目标；关卡卡片右上角加一个审核图标。
 */
@Composable
private fun ProcessNodeCard(
    index: Int,
    stage: AgentTeamStageForm,
    roleLabel: String,
    isReview: Boolean,
    rejectToTitle: String?,
    rejectTargets: List<String>,
    expanded: Boolean,
    onToggleExpand: (Boolean) -> Unit,
    onChange: (AgentTeamStageForm) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
    members: List<String>,
    availableMembers: List<String>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.semanticColors.cardSurface, RoundedCornerShape(Radius.mdLarge))
                .testTag("team_stage_card_${stage.title.ifBlank { index.toString() }}")
                .clickable { onToggleExpand(!expanded) }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$index",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stage.title.takeIf { it.isNotBlank() } ?: stringResource(R.string.team_editor_stage_title_hint, index),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isReview) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.team_editor_stage_review),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = roleLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = Spacing.xs)
                    )
                    if (stage.members.isNotEmpty()) {
                        Text(
                            text = stage.members.joinToString(", "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(end = Spacing.xs),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (stage.interfaces.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.team_interface_count, stage.interfaces.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            if (rejectToTitle != null && rejectToTitle.isNotBlank()) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.team_reject_to, rejectToTitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            if (canRemove) {
                TextButton(onClick = { onRemove() }, contentPadding = PaddingValues(4.dp)) {
                    Text(stringResource(R.string.team_editor_remove_stage), color = MaterialTheme.colorScheme.error)
                }
            }
            Icon(
                imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        if (!expanded) {
            stage.tasks.filter { it.prompt.isNotBlank() }.forEach { task ->
                Text(
                    text = stringResource(R.string.team_task_summary,
                        task.agent.ifBlank { listOf(task.department, task.agentQuery).filter { it.isNotBlank() }.joinToString(" · ") },
                        task.prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.md, top = Spacing.xs),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (expanded) {
            HorizontalDivider(modifier = Modifier.padding(start = 36.dp, end = Spacing.md))
            StageEditor(
                index = index - 1,
                stage = stage,
                members = members,
                availableMembers = availableMembers,
                earlierTitles = rejectTargets,
                onChange = onChange,
                onRemove = onRemove,
                canRemove = canRemove
            )
        }
    }
}

