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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectTaskWorkbench(projectId: String, viewModel: ProjectTaskViewModel, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val project = state.projects.firstOrNull { it.id == projectId }
    val tasks = state.tasks.filter { it.projectId == projectId }
    var viewMode by rememberSaveable(projectId) { mutableStateOf("tree") }
    var collapsed by remember(projectId) { mutableStateOf(emptySet<String>()) }
    var editor by remember { mutableStateOf(false) }
    var appending by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf(false) }
    var text by rememberSaveable(projectId) { mutableStateOf("") }
    var appendRequest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.appendSucceededRequest) {
        if (appendRequest != null && appendRequest == state.appendSucceededRequest) {
            appendRequest = null; appending = false; text = ""
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_back)) }
            TextButton(onClick = { overview = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(project?.title.orEmpty(), maxLines = 1, style = MaterialTheme.typography.titleMedium)
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("project_view_mode")) {
            listOf("tree" to R.string.project_task_tree, "map" to R.string.project_task_map).forEachIndexed { index, (mode, label) ->
                SegmentedButton(selected = viewMode == mode, onClick = { viewMode = mode },
                    shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.heightIn(min = 48.dp).testTag("project_mode_$mode")) { Text(stringResource(label)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextButton(onClick = { appending = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_append)) }
            TextButton(onClick = { editor = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_task_create)) }
        }
        state.error?.let { Text(stringResource(R.string.project_task_error, it), color = MaterialTheme.colorScheme.error) }
        if (viewMode == "map") ProjectTaskMap(projectId, project?.title.orEmpty(), tasks, state.selectedId, onOpenTask,
            Modifier.weight(1f).fillMaxWidth().testTag("project_map_body"))
        else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("project_tree_body"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            item(key = "project-root") { Text(project?.title.orEmpty(), style = MaterialTheme.typography.titleMedium) }
            items(ProjectTaskGraph.visibleTree(tasks, "", null, collapsed), key = { it.task.id }) { row ->
                Row(Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(10) * 12).dp)) {
                    Text("└─", modifier = Modifier.padding(top = Spacing.md))
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable { onOpenTask(row.task.id) }) {
                        Row(Modifier.padding(Spacing.sm)) {
                            Column(Modifier.weight(1f)) {
                                Text(row.task.title, style = MaterialTheme.typography.titleSmall)
                                Text(projectTaskStatusLabel(row.task.status), style = MaterialTheme.typography.labelSmall)
                            }
                            if (tasks.any { it.parentId == row.task.id }) TextButton(onClick = {
                                collapsed = if (row.task.id in collapsed) collapsed - row.task.id else collapsed + row.task.id
                            }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(if (row.task.id in collapsed) R.string.project_task_expand else R.string.project_task_collapse))
                            }
                        }
                    }
                }
            }
        }
    }
    if (editor) ProjectTaskEditor(null, state.workspace, tasks, viewModel::save, { editor = false }, projectId)
    if (overview && project != null) AlertDialog(onDismissRequest = { overview = false },
        title = { Text(project.title) }, text = { Text(project.requirements, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { overview = false }) { Text(stringResource(R.string.project_close)) } })
    if (appending) AdaptiveModalBottomSheet(onDismissRequest = { if (!state.busy) appending = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AppTextField(text, { text = it }, label = stringResource(R.string.project_requirements), minLines = 5, singleLine = false)
            state.error?.let { Text(stringResource(R.string.project_task_error, it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = { val id = java.util.UUID.randomUUID().toString(); appendRequest = id; viewModel.appendRequirements(projectId, text, id) }, enabled = text.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.project_append_generate)) }
        }
    }
}

@Composable
internal fun projectTaskStatusLabel(status: ProjectTaskStatus): String = stringResource(when (status) {
    ProjectTaskStatus.DRAFT -> R.string.project_task_state_draft
    ProjectTaskStatus.READY -> R.string.project_task_state_ready
    ProjectTaskStatus.RUNNING -> R.string.project_task_state_running
    ProjectTaskStatus.REVIEW -> R.string.project_task_state_review
    ProjectTaskStatus.DONE -> R.string.project_task_state_done
    ProjectTaskStatus.BLOCKED -> R.string.project_task_state_blocked
})

@Composable
internal fun projectTaskExecutionReasonLabel(reason: String): String = when (reason) {
    "REQUIREMENTS_CHANGED", "UPSTREAM_CHANGED" -> stringResource(R.string.project_task_requirements_changed)
    "WAITING_DEPENDENCIES" -> stringResource(R.string.project_task_wait_dependencies)
    "STOPPED" -> stringResource(R.string.project_task_stopped)
    "FAILED" -> stringResource(R.string.project_task_failed)
    "INTERRUPTED_PREVIOUS_PROCESS" -> stringResource(R.string.project_task_interrupted)
    "SUCCEEDED" -> stringResource(R.string.project_task_execution_finished)
    else -> reason
}
