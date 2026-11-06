package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.task.*
import com.aharou.feature.agent.presentation.ProjectTaskViewModel

@Composable
fun ProjectTaskDetailScreen(taskId: String, viewModel: ProjectTaskViewModel, onBack: () -> Unit,
    onSelectSession: (String) -> Unit, runExecution: suspend (String, String, String) -> ProjectTaskExecutionOutcome) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val task = state.tasks.firstOrNull { it.id == taskId }
    var editing by remember { mutableStateOf(false) }
    var secondary by rememberSaveable(taskId) { mutableStateOf(false) }
    var adding by rememberSaveable(taskId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
        .padding(Spacing.lg).testTag("project_task_detail"), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_back)) }
        if (task != null) {
            Text(task.title, style = MaterialTheme.typography.headlineSmall)
            Text(projectTaskStatusLabel(task.status))
            Text(task.requirement)
            if (task.projectId != null) TextButton(onClick = { adding = true }, enabled = !state.busy,
                modifier = Modifier.testTag("detail_add_child")) { Text(stringResource(R.string.project_node_add_child)) }
            state.error?.let { Text(stringResource(R.string.project_task_error, it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = { editing = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_task_edit)) }
            Text(stringResource(R.string.project_task_execution_hint))
            Button(onClick = { if (task.projectId != null) viewModel.runProjectTask(task) else viewModel.execute(task, true, runExecution) },
                enabled = !state.busy && task.id !in state.activeTaskIds && (task.projectId == null || task.projectId !in state.activeProjectIds),
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_task_start)) }
            TextButton(onClick = { viewModel.execute(task, false, runExecution) }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_task_new_execution)) }
            if (task.id in state.activeTaskIds) TextButton(onClick = { viewModel.cancelExecution(task.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.project_task_cancel_execution))
            }
            task.sessionIds.forEachIndexed { index, id ->
                TextButton(onClick = { onSelectSession(id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_task_open_execution, index + 1)) }
            }
            if (task.executionReason.isNotBlank()) Text(projectTaskExecutionReasonLabel(task.executionReason))
            TextButton(onClick = { secondary = !secondary }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_task_collaboration_evidence)) }
            if (secondary) {
                Text(task.background)
                Text(stringResource(R.string.project_task_collaboration_value, task.owner, task.members.joinToString("、")))
                Text(task.constraints.joinToString("\n"))
                Text(task.acceptance + "\n" + task.brief + "\n" + task.evidenceRefs.joinToString("\n"))
            }
            Button(onClick = { viewModel.accept(task) }, enabled = !state.busy && task.status == ProjectTaskStatus.REVIEW && task.acceptance.isNotBlank() && task.evidenceRefs.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_task_accept)) }
        } else Text(stringResource(R.string.project_task_empty))
    }
    val projectId = task?.projectId
    if (adding && task != null && projectId != null) ProjectTaskAddSheet(task.title, state.busy,
        onSubmit = { description ->
            viewModel.addTaskByDescription(projectId, description, parentId = task.id)
            adding = false
        }, onDismiss = { adding = false })
    if (editing && task != null) ProjectTaskEditor(task, task.workspace, state.tasks, viewModel::save, { editing = false }, task.projectId)
}
