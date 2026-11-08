package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.task.*
import com.aharou.feature.agent.presentation.ProjectTaskViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.MoreVertical

@Composable
fun ProjectTaskDetailScreen(taskId: String, viewModel: ProjectTaskViewModel, onBack: () -> Unit,
    onSelectSession: (String) -> Unit, runExecution: suspend (String, String, String) -> ProjectTaskExecutionOutcome) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val task = state.tasks.firstOrNull { it.id == taskId }
    var editing by rememberSaveable(taskId) { mutableStateOf(false) }
    var overview by rememberSaveable(taskId) { mutableStateOf(false) }
    var menuOpen by rememberSaveable(taskId) { mutableStateOf(false) }
    var adding by rememberSaveable(taskId) { mutableStateOf(false) }
    val running = taskId in state.activeTaskIds
    val ready = !state.loading && !state.busy && task != null &&
        (task.projectId == null || task.projectId !in state.activeProjectIds)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(horizontal = Spacing.sm).testTag("project_task_detail"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_compact_header"),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(FeatherIcons.ChevronLeft, stringResource(R.string.project_back))
            }
            Column(Modifier.weight(1f)) {
                Text(task?.title ?: stringResource(R.string.project_unassigned),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                task?.let { Text(projectTaskStatusLabel(it.status), style = MaterialTheme.typography.labelSmall) }
            }
            if (task != null) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(FeatherIcons.MoreVertical, stringResource(R.string.project_more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_task_overview)) },
                        onClick = { menuOpen = false; overview = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_task_edit)) }, enabled = !state.loading && !state.busy,
                        onClick = { menuOpen = false; editing = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_task_new_execution)) }, enabled = ready && !running,
                        onClick = { menuOpen = false; viewModel.execute(task, false, runExecution) })
                    if (task.projectId != null) DropdownMenuItem(text = { Text(stringResource(R.string.project_node_add_child)) },
                        enabled = ready, modifier = Modifier.testTag("detail_add_child"),
                        onClick = { menuOpen = false; adding = true })
                }
            }
        }
        state.error?.let {
            Text(stringResource(R.string.project_task_error, projectTaskExecutionReasonLabel(it)),
                color = MaterialTheme.colorScheme.error)
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (task != null) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (running) OutlinedButton(onClick = { viewModel.cancelExecution(task.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_stop")) {
                Text(stringResource(R.string.project_task_cancel_execution))
            } else Button(onClick = {
                if (task.projectId != null) viewModel.runProjectTask(task) else viewModel.execute(task, true, runExecution)
            }, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_start")) {
                Text(stringResource(R.string.project_task_start))
            }
            ProjectTaskProcess(task, state.tasks, onEdit = { editing = true },
                onAccept = { viewModel.accept(task) }, readOnly = true, fillConversation = false,
                acceptEnabled = !state.loading && !state.busy,
                modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
                conversation = { _, id ->
                    TextButton(onClick = { onSelectSession(id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.project_task_open_record))
                    }
                })
        } else if (!state.loading) Text(stringResource(R.string.project_task_empty))
    }
    if (overview && task != null) AlertDialog(onDismissRequest = { overview = false },
        title = { Text(task.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(task.requirement)
                if (task.background.isNotBlank()) Text(task.background)
                Text(stringResource(R.string.project_task_collaboration_value, task.owner, task.members.joinToString("、")))
                if (task.constraints.isNotEmpty()) Text(task.constraints.joinToString("\n"))
                if (task.acceptance.isNotBlank()) Text(task.acceptance)
                if (task.brief.isNotBlank()) Text(task.brief)
                if (task.evidenceRefs.isNotEmpty()) Text(task.evidenceRefs.joinToString("\n"))
            }
        }, confirmButton = { TextButton(onClick = { overview = false }) { Text(stringResource(R.string.project_close)) } })
    val projectId = task?.projectId
    if (adding && task != null && projectId != null) ProjectTaskAddSheet(task.title, state.busy,
        onSubmit = { description ->
            viewModel.addTaskByDescription(projectId, description, parentId = task.id)
            adding = false
        }, onDismiss = { adding = false })
    if (editing && task != null) ProjectTaskEditor(task, task.workspace, state.tasks, viewModel::save, { editing = false }, task.projectId)
}
