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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.task.*
import com.aharou.feature.agent.presentation.ProjectTaskViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.Layers
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Play
import compose.icons.feathericons.Square

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectTaskWorkbench(projectId: String, viewModel: ProjectTaskViewModel, onBack: () -> Unit,
    conversation: @Composable (ProjectTask, String) -> Unit, approvals: @Composable () -> Unit = {},
    onContentDrag: () -> Unit = {}, rootConversation: @Composable () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val project = state.projects.firstOrNull { it.id == projectId }
    val tasks = state.tasks.filter { it.projectId == projectId }
    val rootId = ProjectTaskMapLayout.rootId(projectId)
    var viewMode by rememberSaveable(projectId) { mutableStateOf("map") }
    var menuOpen by rememberSaveable(projectId) { mutableStateOf(false) }
    var expandedId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var collapsed by remember(projectId) { mutableStateOf(emptySet<String>()) }
    var overview by remember { mutableStateOf(false) }
    var adding by rememberSaveable(projectId) { mutableStateOf(false) }
    var addingParentId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
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
    val sessionMenu = LocalProjectChatMenu.current
    val header: @Composable (@Composable (() -> Unit) -> Unit) -> Unit = { mapMenu ->
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("project_compact_header"),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(FeatherIcons.ChevronLeft, stringResource(R.string.project_back))
            }
            Column(Modifier.weight(1f).clickable { overview = true }.padding(vertical = Spacing.xs)) {
                Text(project?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.project_flow_progress, tasks.count { ProjectWorkflowPlan.delivered(it) }, tasks.size),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(FeatherIcons.MoreVertical, stringResource(R.string.project_view_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (projectId in state.activeProjectIds) DropdownMenuItem(
                        text = { Text(stringResource(R.string.project_flow_stop)) },
                        leadingIcon = { Icon(FeatherIcons.Square, null) },
                        modifier = Modifier.testTag("project_flow_stop"),
                        onClick = { menuOpen = false; viewModel.stopProject(projectId) })
                    else DropdownMenuItem(text = { Text(stringResource(R.string.project_flow_start)) },
                        leadingIcon = { Icon(FeatherIcons.Play, null) },
                        enabled = !state.loading && !state.busy && !planning && tasks.any { !ProjectWorkflowPlan.delivered(it) },
                        modifier = Modifier.testTag("project_flow_start"),
                        onClick = { menuOpen = false; viewModel.startProject(projectId) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_task_overview)) },
                        onClick = { menuOpen = false; overview = true })
                    DropdownMenuItem(
                        text = { Text(stringResource(if (viewMode == "map") R.string.project_task_tree else R.string.project_task_map)) },
                        modifier = Modifier.testTag("project_mode_toggle"),
                        onClick = { expandedId = null; viewMode = if (viewMode == "map") "tree" else "map"; menuOpen = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_add_task)) }, enabled = !state.busy,
                        modifier = Modifier.testTag("project_add_task"),
                        onClick = { addingParentId = null; adding = true; menuOpen = false })
                    mapMenu { menuOpen = false }
                    sessionMenu { menuOpen = false }
                }
            }
        }
        if (planning) Column(Modifier.fillMaxWidth().testTag("project_planning"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.project_planning), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (ruleFallback) Surface(shape = RoundedCornerShape(Radius.mdLarge), color = MaterialTheme.semanticColors.warningContainer,
            contentColor = MaterialTheme.semanticColors.onWarningContainer, modifier = Modifier.fillMaxWidth().testTag("project_rule_fallback")) {
            Text(stringResource(R.string.project_rule_fallback), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(Spacing.sm))
        }
        state.error?.let { Text(stringResource(R.string.project_task_error, projectTaskExecutionReasonLabel(it)), color = MaterialTheme.colorScheme.error) }
        approvals()
        }
    }
    val dragConnection = rememberContentDragConnection(onContentDrag)
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().nestedScroll(dragConnection).padding(horizontal = Spacing.sm)) {
            val processHeight = (maxHeight * 0.9f).coerceIn(240.dp, 640.dp)
            if (viewMode == "map") ProjectTaskMap(projectId, project?.title.orEmpty(), tasks, processId = expandedId,
                onOpenProcess = { id -> expandedId = id; viewModel.select(id) }, onDismissProcess = { expandedId = null },
                onSupplement = {}, onAddChild = { task -> addingParentId = task?.id; adding = true }, onContentDrag = onContentDrag,
                onOpenRoot = { toggle(rootId) }, rootConversation = rootConversation,
                modifier = Modifier.fillMaxSize().testTag("project_map_body"), header = header, process = process)
            else Column(Modifier.fillMaxSize()) {
                header {}
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("project_tree_body"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
                        if (expanded) {
                            TextButton(onClick = { addingParentId = row.task.id; adding = true }, enabled = !state.busy,
                                modifier = Modifier.testTag("task_add_child_${row.task.id}")) {
                                Text(stringResource(R.string.project_node_add_child))
                            }
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth().height(processHeight)) { process(row.task) }
                        }
                    }
                }
            }
        }
    }
    if (adding) ProjectTaskAddSheet(parentTitle = tasks.firstOrNull { it.id == addingParentId }?.title,
        busy = state.busy, onSubmit = { description ->
            viewModel.addTaskByDescription(projectId, description, parentId = addingParentId)
            adding = false
        }, onDismiss = { adding = false })
    if (overview && project != null) AlertDialog(onDismissRequest = { overview = false },
        title = { Text(project.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(project.requirements)
                com.aharou.core.translation.TranslationPreview(project.requirements)
            }
        },
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
    "PROJECT_PLANNING" -> stringResource(R.string.project_delete_planning)
    "PROJECT_NOT_READY" -> stringResource(R.string.project_delete_not_ready)
    "PROJECT_BUSY" -> stringResource(R.string.project_workspace_busy)
    "FAILED", "TASK_EXECUTION_FAILED" -> stringResource(R.string.project_task_failed)
    "INTERRUPTED_PREVIOUS_PROCESS" -> stringResource(R.string.project_task_interrupted)
    "SUCCEEDED" -> stringResource(R.string.project_task_execution_finished)
    else -> reason
}
