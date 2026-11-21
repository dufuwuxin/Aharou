package com.aharou.feature.agent.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.aharou.feature.agent.domain.task.ProjectPlan
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.feature.agent.presentation.ProjectTaskUiState
import com.aharou.feature.agent.presentation.ProjectTaskViewModel

@Composable
internal fun ProjectGoalDialog(project: ProjectPlan, state: ProjectTaskUiState, viewModel: ProjectTaskViewModel, onDismiss: () -> Unit) {
    var goal by rememberSaveable(project.id) { mutableStateOf(project.overallGoal) }
    var acceptance by rememberSaveable(project.id) { mutableStateOf(project.goalAcceptance) }
    var horizon by rememberSaveable(project.id) { mutableStateOf(project.horizon) }
    var team by rememberSaveable(project.id) { mutableStateOf(project.executionTeam.orEmpty()) }
    val busy = state.busy || project.id in state.activeProjectIds
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.pmap_edit_goal)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("short" to R.string.project_horizon_short, "medium" to R.string.project_horizon_medium,
                    "long" to R.string.project_horizon_long).forEach { (value, label) ->
                    FilterChip(selected = horizon == value, enabled = !busy, onClick = { horizon = value }, label = { Text(stringResource(label)) })
                }
            }
            OutlinedTextField(goal, { goal = it }, enabled = !busy, minLines = 2, label = { Text(stringResource(R.string.project_overall_goal)) })
            OutlinedTextField(acceptance, { acceptance = it }, enabled = !busy, minLines = 2, label = { Text(stringResource(R.string.project_goal_acceptance)) })
            OutlinedTextField(team, { team = it }, enabled = !busy, label = { Text(stringResource(R.string.project_execution_team)) })
            Text(stringResource(R.string.project_execution_team_hint), style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(projectTaskExecutionReasonLabel(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            viewModel.saveProjectGoal(project, horizon, goal, acceptance,
                team.ifBlank { if (project.executionTeam == null) null else "auto" }, onSaved = onDismiss)
        }) { Text(stringResource(R.string.project_goal_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

/** Read-only planning, followed by an editable proposal. Only confirmation writes tasks. */
@Composable
internal fun ProjectRequirementEditor(projectId: String, state: ProjectTaskUiState, viewModel: ProjectTaskViewModel,
    text: String, onText: (String) -> Unit, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val preview = state.requirementPreview?.takeIf { it.project.id == projectId }
    val busy = state.busy || projectId in state.planningProjectIds
    val valid = preview != null && preview.tasks.isNotEmpty() &&
        preview.tasks.all { it.title.isNotBlank() && it.requirement.isNotBlank() }
    val listState = rememberLazyListState()
    LaunchedEffect(preview?.requirement) { listState.scrollToItem(0) }
    val back: () -> Unit = { if (!busy) { if (preview != null) viewModel.dismissRequirementPreview() else onDismiss() } }
    BackHandler(onBack = back)
    fun confirm(start: Boolean) {
        val request = java.util.UUID.randomUUID().toString()
        onSubmit(request)
        viewModel.confirmRequirementPreview(request, start)
    }
    Column(Modifier.fillMaxSize().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = back) {
                Text(stringResource(if (preview != null) R.string.pmap_edit_requirement else android.R.string.cancel))
            }
            Text(stringResource(if (preview == null) R.string.pmap_add_requirements else R.string.pmap_plan_preview),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(top = 8.dp))
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (preview == null) item("input") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pmap_add_hint))
                    OutlinedTextField(text, onText, enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 5,
                        label = { Text(stringResource(R.string.project_requirements)) })
                }
            } else {
                item("source") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.pmap_preview_count, preview.tasks.size))
                        Text(preview.requirement)
                        if (preview.ruleFallback) Text(stringResource(R.string.pmap_plan_fallback), color = MaterialTheme.colorScheme.error)
                        if (preview.tasks.isEmpty()) Text(stringResource(R.string.pmap_plan_empty))
                    }
                }
                itemsIndexed(preview.tasks, key = { _, task -> task.id }) { index, task ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text(stringResource(R.string.pmap_preview_task, index + 1), style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(task.title, { viewModel.editPreviewTask(task.id, it, task.requirement, task.acceptance) },
                            enabled = !busy, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pmap_task_title)) })
                        OutlinedTextField(task.requirement, { viewModel.editPreviewTask(task.id, task.title, it, task.acceptance) },
                            enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 2,
                            label = { Text(stringResource(R.string.project_requirements)) })
                        OutlinedTextField(task.acceptance, { viewModel.editPreviewTask(task.id, task.title, task.requirement, it) },
                            enabled = !busy, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pmap_acceptance)) })
                        if (task.dependencyIds.isNotEmpty()) Text(stringResource(R.string.pmap_preview_dependencies,
                            task.dependencyIds.joinToString("、") { id -> (preview.tasks + preview.existingTasks).firstOrNull { it.id == id }?.title ?: id }))
                    }
                }
            }
        }
        state.error?.let { Text(projectTaskExecutionReasonLabel(it), color = MaterialTheme.colorScheme.error) }
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.project_planning)) }
        HorizontalDivider()
        if (preview == null) Button(enabled = !busy && text.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            viewModel.previewRequirements(projectId, text.trim())
        }) { Text(stringResource(R.string.pmap_plan_requirements)) }
        else {
            OutlinedButton(enabled = !busy && valid, modifier = Modifier.fillMaxWidth(), onClick = { confirm(false) }) {
                Text(stringResource(R.string.pmap_save_draft))
            }
            if (!preview.ruleFallback) Button(enabled = !busy && valid, modifier = Modifier.fillMaxWidth(), onClick = { confirm(true) }) {
                Text(stringResource(R.string.pmap_save_and_start))
            }
        }
    }
}
