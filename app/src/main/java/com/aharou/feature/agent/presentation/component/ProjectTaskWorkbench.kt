package com.aharou.feature.agent.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.task.*
import com.aharou.feature.agent.presentation.ProjectTaskViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectTaskWorkbench(projectId: String, viewModel: ProjectTaskViewModel, onBack: () -> Unit,
    conversation: @Composable (ProjectTask, String) -> Unit, approvals: @Composable () -> Unit = {},
    onContentDrag: () -> Unit = {}, rootConversation: @Composable () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val project = state.projects.firstOrNull { it.id == projectId }
    val tasks = state.tasks.filter { it.projectId == projectId }
    val rootId = ProjectTaskMapLayout.rootId(projectId)
    var viewMode by rememberSaveable(projectId) { mutableStateOf("tree") }
    var expandedId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var collapsed by remember(projectId) { mutableStateOf(emptySet<String>()) }
    var overview by remember { mutableStateOf(false) }
    val planning = projectId in state.planningProjectIds
    val ruleFallback = projectId in state.ruleFallbackProjectIds
    LaunchedEffect(state.selectedId) { if (tasks.any { it.id == state.selectedId }) expandedId = state.selectedId }
    BackHandler(enabled = expandedId != null) { expandedId = null }
    val toggle: (String) -> Unit = { id ->
        if (expandedId == id) expandedId = null else { expandedId = id; viewModel.select(id) }
    }
    val process: @Composable (ProjectTask) -> Unit = { task ->
        ProjectTaskProcess(task, tasks, onEdit = {}, onAccept = { viewModel.accept(task) }, conversation = conversation,
            modifier = Modifier.fillMaxSize().padding(Spacing.sm), readOnly = true)
    }
    val dragConnection = rememberContentDragConnection(onContentDrag)
    Column(Modifier.fillMaxSize().nestedScroll(dragConnection)
        .padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_back)) }
            TextButton(onClick = { overview = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(project?.title.orEmpty(), maxLines = 1, style = MaterialTheme.typography.titleMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (projectId in state.activeProjectIds) Button(onClick = { viewModel.stopProject(projectId) }, modifier = Modifier.testTag("project_flow_stop")) {
                Text(stringResource(R.string.project_flow_stop))
            } else Button(onClick = { viewModel.startProject(projectId) }, enabled = !state.loading && !state.busy && !planning && tasks.any { !ProjectWorkflowPlan.delivered(it) },
                modifier = Modifier.testTag("project_flow_start")) { Text(stringResource(R.string.project_flow_start)) }
            Text(stringResource(R.string.project_flow_progress, tasks.count { ProjectWorkflowPlan.delivered(it) }, tasks.size),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f).padding(top = Spacing.sm))
        }
        if (planning) Column(Modifier.fillMaxWidth().testTag("project_planning"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.project_planning), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (ruleFallback) Surface(shape = RoundedCornerShape(Radius.mdLarge), color = MaterialTheme.semanticColors.warningContainer,
            contentColor = MaterialTheme.semanticColors.onWarningContainer, modifier = Modifier.fillMaxWidth().testTag("project_rule_fallback")) {
            Text(stringResource(R.string.project_rule_fallback), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(Spacing.sm))
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("project_view_mode")) {
            listOf("tree" to R.string.project_task_tree, "map" to R.string.project_task_map).forEachIndexed { index, (mode, label) ->
                SegmentedButton(selected = viewMode == mode, onClick = { if (viewMode != mode) expandedId = null; viewMode = mode },
                    shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.heightIn(min = 48.dp).testTag("project_mode_$mode")) { Text(stringResource(label)) }
            }
        }
        state.error?.let { Text(stringResource(R.string.project_task_error, projectTaskExecutionReasonLabel(it)), color = MaterialTheme.colorScheme.error) }
        approvals()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val processHeight = (maxHeight * 0.9f).coerceIn(240.dp, 640.dp)
            if (viewMode == "map") ProjectTaskMap(projectId, project?.title.orEmpty(), tasks, processId = expandedId,
                onOpenProcess = { id -> expandedId = id; viewModel.select(id) }, onDismissProcess = { expandedId = null },
                onSupplement = {}, onAddChild = {}, onContentDrag = onContentDrag,
                onOpenRoot = { toggle(rootId) }, rootConversation = rootConversation,
                modifier = Modifier.fillMaxSize().testTag("project_map_body"), process = process)
            else LazyColumn(Modifier.fillMaxSize().testTag("project_tree_body"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                item(key = "project-root") {
                    Surface(shape = RoundedCornerShape(Radius.mdLarge), color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable { toggle(rootId) }
                            .testTag("task_node_root")) {
                        Column(Modifier.padding(Spacing.sm)) {
                            Text(project?.title.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.project_process_hint), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (expandedId == rootId) item(key = "project-root-conversation") {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().height(processHeight).testTag("project_root_conversation")) {
                        rootConversation()
                    }
                }
                items(ProjectTaskGraph.visibleTree(tasks, "", null, collapsed), key = { it.task.id }) { row ->
                    val expanded = expandedId == row.task.id
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(10) * 12).dp)) {
                            Text("└─", modifier = Modifier.padding(top = Spacing.md))
                            Surface(shape = MaterialTheme.shapes.medium,
                                color = if (expanded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .clickable { toggle(row.task.id) }
                                    .testTag("task_node_${row.task.id}")) {
                                Row(Modifier.padding(Spacing.sm)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(row.task.title, style = MaterialTheme.typography.titleSmall)
                                        Text(projectTaskStatusLabel(row.task.status), style = MaterialTheme.typography.labelSmall)
                                        Text(row.task.owner.ifBlank { stringResource(R.string.project_flow_default_member) }, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(stringResource(if (expanded) R.string.project_process_collapse else R.string.project_process_expand), style = MaterialTheme.typography.labelSmall)
                                    if (tasks.any { it.parentId == row.task.id }) TextButton(onClick = {
                                        collapsed = if (row.task.id in collapsed) collapsed - row.task.id else collapsed + row.task.id
                                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text(stringResource(if (row.task.id in collapsed) R.string.project_task_expand else R.string.project_task_collapse))
                                    }
                                }
                            }
                        }
                        if (expanded) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().height(processHeight)) { process(row.task) }
                    }
                }
            }
        }
    }
    if (overview && project != null) AlertDialog(onDismissRequest = { overview = false },
        title = { Text(project.title) }, text = { Text(project.requirements, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { overview = false }) { Text(stringResource(R.string.project_close)) } })

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
