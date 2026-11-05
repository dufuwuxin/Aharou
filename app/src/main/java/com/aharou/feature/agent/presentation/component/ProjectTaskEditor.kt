package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.task.ProjectTask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectTaskEditor(task: ProjectTask?, workspace: String, tasks: List<ProjectTask>,
    onSave: (ProjectTask) -> Unit, onDismiss: () -> Unit, projectId: String? = task?.projectId,
    availableMembers: List<String> = emptyList()) {
    val seed = remember(task?.id, workspace, projectId) { task ?: ProjectTask(workspace = workspace, projectId = projectId, title = "", requirement = "") }
    var title by remember(seed) { mutableStateOf(seed.title) }
    var requirement by remember(seed) { mutableStateOf(seed.requirement) }
    var background by remember(seed) { mutableStateOf(seed.background) }
    var acceptance by remember(seed) { mutableStateOf(seed.acceptance) }
    var owner by remember(seed) { mutableStateOf(seed.owner) }
    var members by remember(seed) { mutableStateOf(seed.members.joinToString("\n")) }
    var constraints by remember(seed) { mutableStateOf(seed.constraints.joinToString("\n")) }
    var brief by remember(seed) { mutableStateOf(seed.brief) }
    var evidence by remember(seed) { mutableStateOf(seed.evidenceRefs.joinToString("\n")) }
    var dependencyIds by remember(seed) { mutableStateOf(seed.dependencyIds.toSet()) }
    var dependencyMenu by remember { mutableStateOf(false) }
    var parentId by remember(seed) { mutableStateOf(seed.parentId) }
    var details by remember { mutableStateOf(false) }
    var parentMenu by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf(false) }
    val descendants = remember(tasks, seed.id) {
        val children = tasks.groupBy { it.parentId }
        val seen = mutableSetOf(seed.id); val queue = java.util.ArrayDeque<String>(); queue.add(seed.id)
        while (queue.isNotEmpty()) children[queue.removeFirst()].orEmpty().forEach { if (seen.add(it.id)) queue.add(it.id) }
        seen
    }
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(if (task == null) R.string.project_task_add_one else R.string.project_task_edit), style = MaterialTheme.typography.titleLarge)
            if (task == null) Text(stringResource(R.string.project_task_add_one_hint), style = MaterialTheme.typography.bodySmall)
            AppTextField(title, { title = it }, label = stringResource(R.string.project_task_title), singleLine = true)
            AppTextField(requirement, { requirement = it }, label = stringResource(R.string.project_task_requirement), minLines = 3, singleLine = false)
            Box {
                OutlinedButton(onClick = { memberMenu = true }) {
                    Text(stringResource(R.string.project_task_owner_value, owner.ifBlank { stringResource(R.string.project_flow_default_member) }))
                }
                DropdownMenu(expanded = memberMenu, onDismissRequest = { memberMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_flow_default_member)) }, onClick = { owner = ""; memberMenu = false })
                    availableMembers.forEach { member -> DropdownMenuItem(text = { Text(member) }, onClick = { owner = member; memberMenu = false }) }
                }
            }
            if (details) AppTextField(background, { background = it }, label = stringResource(R.string.project_task_background), minLines = 2, singleLine = false)
            if (details) AppTextField(acceptance, { acceptance = it }, label = stringResource(R.string.project_task_acceptance), minLines = 2, singleLine = false)
            Box {
                OutlinedButton(onClick = { parentMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.project_task_parent_value, tasks.firstOrNull { it.id == parentId }?.title ?: stringResource(R.string.project_task_root)))
                }
                DropdownMenu(expanded = parentMenu, onDismissRequest = { parentMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_task_root)) }, onClick = { parentId = null; parentMenu = false })
                    tasks.filter { it.workspace == workspace && it.projectId == seed.projectId && it.id !in descendants }.forEach { parent ->
                        DropdownMenuItem(text = { Text(parent.title) }, onClick = { parentId = parent.id; parentMenu = false })
                    }
                }
            }
            Box {
                OutlinedButton(onClick = { dependencyMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.project_task_dependencies, dependencyIds.size))
                }
                DropdownMenu(expanded = dependencyMenu, onDismissRequest = { dependencyMenu = false }) {
                    tasks.filter { it.id != seed.id && it.workspace == workspace && it.projectId == seed.projectId }.forEach { candidate ->
                        DropdownMenuItem(text = { Text((if (candidate.id in dependencyIds) "✓ " else "") + candidate.title) }, onClick = {
                            dependencyIds = if (candidate.id in dependencyIds) dependencyIds - candidate.id else dependencyIds + candidate.id
                        })
                    }
                }
            }
            TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_task_collaboration_evidence)) }
            if (details) {
                AppTextField(members, { members = it }, label = stringResource(R.string.project_task_members))
                AppTextField(constraints, { constraints = it }, label = stringResource(R.string.project_task_constraints))
                AppTextField(brief, { brief = it }, label = stringResource(R.string.project_task_brief), minLines = 2, singleLine = false)
                AppTextField(evidence, { evidence = it }, label = stringResource(R.string.project_task_evidence), minLines = 2, singleLine = false)
            }
            Button(onClick = {
                onSave(seed.copy(title = title.trim(), requirement = requirement.trim(), background = background,
                    acceptance = acceptance, owner = owner.trim(), members = members.lines().filter(String::isNotBlank),
                    constraints = constraints.lines().filter(String::isNotBlank), brief = brief,
                    evidenceRefs = evidence.lines().filter(String::isNotBlank), parentId = parentId, dependencyIds = dependencyIds.toList()))
                onDismiss()
            }, enabled = title.isNotBlank() && requirement.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.project_task_save))
            }
        }
    }
}
