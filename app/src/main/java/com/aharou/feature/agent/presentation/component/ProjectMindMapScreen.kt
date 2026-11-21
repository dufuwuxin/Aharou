package com.aharou.feature.agent.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.task.*
import com.aharou.feature.agent.presentation.ProjectTaskViewModel
import kotlinx.coroutines.launch

/** Independent read-only project route. Opening this screen never binds or creates a conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectMindMapScreen(projectId: String, viewModel: ProjectTaskViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val project = state.projects.singleOrNull { it.id == projectId }
    LaunchedEffect(projectId, project?.workspace) {
        if (project != null && state.workspace != project.workspace) viewModel.openProject(projectId) {}
    }
    Scaffold(containerColor = MaterialTheme.semanticColors.pageBackground,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).testTag("project_mind_map")) {
            if (project == null || state.workspace != project.workspace || state.loading && state.tasks.isEmpty()) {
                TopAppBar(title = { Text(stringResource(R.string.pmap_title)) }, navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.project_back))
                    }
                })
                Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        when {
                            state.error != null -> {
                                Text(stringResource(R.string.pmap_project_error, state.error.orEmpty()))
                                if (project != null) OutlinedButton(onClick = { viewModel.openProject(projectId) {} },
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pmap_reload)) }
                            }
                            state.loading || project != null -> {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.pmap_loading))
                            }
                            else -> Text(stringResource(R.string.pmap_unavailable))
                        }
                    }
                }
            } else key(project.workspace, project.id) {
                ProjectMapContent(project, ProjectMindMapModels.tasks(project, state.tasks), state.activeTaskIds,
                    state.error, viewModel, onBack)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectMapContent(project: ProjectPlan, tasks: List<ProjectTask>, activeTaskIds: Set<String>,
    error: String?, viewModel: ProjectTaskViewModel, onBack: () -> Unit) {
    var history by rememberSaveable { mutableStateOf(arrayListOf("map")) }
    var anchor by rememberSaveable { mutableStateOf("map") }
    var removed by rememberSaveable { mutableStateOf(false) }
    var detailTarget by remember { mutableStateOf<String?>(null) }
    var mapRequest by remember { mutableIntStateOf(0) }
    val selection = history.lastOrNull() ?: "map"
    val roles = remember(tasks) { ProjectMindMapModels.roles(tasks) }
    val projectState by viewModel.state.collectAsStateWithLifecycle()
    val active = project.id in projectState.activeProjectIds
    val busy = projectState.busy || projectState.loading || project.id in projectState.planningProjectIds
    var adding by rememberSaveable { mutableStateOf(false) }
    var requirement by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingGoal by rememberSaveable { mutableStateOf(false) }
    tasks.firstOrNull { it.id == editingTaskId }?.let { task ->
        ProjectTaskSupplementSheet(task, busy, onSubmit = { text ->
            viewModel.supplement(task, text) { editingTaskId = null }
        }, onDismiss = { editingTaskId = null })
    }
    if (editingGoal) ProjectGoalDialog(project, projectState, viewModel, onDismiss = { editingGoal = false })
    LaunchedEffect(projectState.appendSucceededRequest) {
        if (submitted != null && submitted == projectState.appendSucceededRequest) {
            adding = false; requirement = ""; submitted = null
        }
    }
    if (adding) {
        ProjectRequirementEditor(project.id, projectState, viewModel, requirement,
            onText = { requirement = it }, onSubmit = { submitted = it },
            onDismiss = { viewModel.dismissRequirementPreview(); adding = false })
        return
    }
    val selectedTask = tasks.firstOrNull { "task:${it.id}" == selection }
    val selectedRole = selection.removePrefix("role:").takeIf { selection.startsWith("role:") }
    val navigate: (String) -> Unit = { target ->
        if (target != selection) {
            history = ArrayList(ProjectMindMapModels.navigate(history, target))
            anchor = target
            removed = false
            detailTarget = target
        }
    }
    val back: () -> Unit = {
        if (history.size > 1) {
            history = ArrayList(history.dropLast(1))
            anchor = history.last()
        } else onBack()
    }
    BackHandler(history.size > 1, onBack = back)
    LaunchedEffect(selection, tasks, roles) {
        if (selection.startsWith("task:") && selectedTask == null ||
            selection.startsWith("role:") && selectedRole !in roles) {
            history = arrayListOf("map"); anchor = "map"; removed = true
        }
    }
    TopAppBar(title = { Text(project.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
        IconButton(onClick = back, modifier = Modifier.size(48.dp).testTag("pmap_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.project_back))
        }
    }, actions = {
        TextButton(enabled = !busy && !active, onClick = { adding = true }, modifier = Modifier.testTag("pmap_add")) {
            Text(stringResource(R.string.pmap_add_requirements))
        }
        TextButton(enabled = active || !busy && (tasks.any { !it.isRequirementBranch && !ProjectWorkflowPlan.delivered(it) } ||
            tasks.isEmpty() && (project.requirements.isNotBlank() || project.purpose.isNotBlank() || project.overallGoal.isNotBlank())),
            onClick = { if (active) viewModel.stopProject(project.id) else viewModel.startProject(project.id) },
            modifier = Modifier.testTag("pmap_start_stop")) {
            Text(stringResource(when {
                active -> R.string.project_flow_stop
                tasks.any { it.status == ProjectTaskStatus.BLOCKED } -> R.string.pmap_resume_project
                else -> R.string.project_flow_start
            }))
        }
    })
    val overviewCount = tasks.count { ProjectMindMapModels.accepted(it) }
    val total = tasks.count { !it.isRequirementBranch }
    val holder = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = Spacing.lg)) {
        val wide = maxWidth >= 720.dp
        val mapListState = rememberLazyListState()
        val nodes = remember { mutableMapOf<String, Pair<BringIntoViewRequester, FocusRequester>>() }
        val scope = rememberCoroutineScope()
        val focusMap: () -> Unit = { mapRequest++ }
        val map: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (error != null) Text(projectTaskExecutionReasonLabel(error), color = MaterialTheme.colorScheme.error)
                if (project.id in projectState.ruleFallbackProjectIds)
                    Text(stringResource(R.string.pmap_plan_fallback), color = MaterialTheme.colorScheme.error)
                ProjectMapCanvas(project, tasks, roles, if (selection == "map") anchor else selection,
                    overviewCount, total, nodes, navigate)
            }
        }
        val detail: @Composable () -> Unit = {
            holder.SaveableStateProvider(selection) {
                val listState = rememberLazyListState()
                val headingBring = remember { BringIntoViewRequester() }
                val headingFocus = remember { FocusRequester() }
                LaunchedEffect(selection, detailTarget) {
                    if (detailTarget == selection) {
                        listState.scrollToItem(if (wide) 0 else 1)
                        headingBring.bringIntoView()
                        headingFocus.requestFocus()
                        detailTarget = null
                    }
                }
                LaunchedEffect(mapRequest) {
                    if (mapRequest > 0) {
                        if (wide) mapListState.scrollToItem(0) else listState.scrollToItem(0)
                        nodes[anchor]?.let { (bring, focus) -> bring.bringIntoView(); focus.requestFocus() }
                        mapRequest = 0
                    }
                }
                val title = selectedTask?.title ?: selectedRole ?: when (selection) {
                    "team" -> stringResource(R.string.pmap_team)
                    "work" -> stringResource(R.string.pmap_work)
                    else -> project.title
                }
                val taskDetail = selectedTask?.let { rememberMapTaskDetail(it, viewModel) }
                LaunchedEffect(taskDetail?.jumpId, taskDetail?.receipts) {
                    val detailState = taskDetail ?: return@LaunchedEffect
                    val target = detailState.jumpId ?: return@LaunchedEffect
                    val index = detailState.receipts.indexOfFirst { it.id == target }
                    if (index >= 0) listState.scrollToItem((if (wide) 1 else 2) + 4 + index)
                    detailState.jumpId = null
                }
                val headingModifier = Modifier.fillMaxWidth().bringIntoViewRequester(headingBring)
                    .focusRequester(headingFocus).focusable().semantics { heading() }
                    .testTag("pmap_detail_heading")
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("pmap_reading"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    contentPadding = PaddingValues(bottom = Spacing.xl)) {
                    if (!wide) item("map_canvas") { map() }
                    item("detail_heading") {
                        Surface(shape = RoundedCornerShape(Radius.lg), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                if (removed) Text(stringResource(R.string.pmap_selection_removed))
                                Text(title, headingModifier, style = MaterialTheme.typography.titleMedium)
                                selectedTask?.let { MapTaskStatus(it, it.id in activeTaskIds) }
                            }
                        }
                    }
                    if (selectedTask != null && taskDetail != null) {
                        projectTaskDetailItems(selectedTask, tasks, activeTaskIds, taskDetail, navigate)
                        item("task_actions") {
                            if (!selectedTask.isRequirementBranch) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                TextButton(enabled = !busy && !active, onClick = { editingTaskId = selectedTask.id }) {
                                    Text(stringResource(R.string.pmap_edit_task))
                                }
                                if (selectedTask.status == ProjectTaskStatus.REVIEW) TextButton(enabled = !busy && !active &&
                                    selectedTask.acceptance.isNotBlank() && selectedTask.evidenceRefs.isNotEmpty(),
                                    onClick = { viewModel.accept(selectedTask) }) { Text(stringResource(R.string.project_task_accept)) }
                                else TextButton(enabled = !busy && !active && selectedTask.id !in activeTaskIds &&
                                    !ProjectWorkflowPlan.delivered(selectedTask) && ProjectTaskGraph.dependenciesReady(selectedTask, tasks),
                                    onClick = { viewModel.runProjectTask(selectedTask) }) { Text(stringResource(R.string.pmap_run_task)) }
                            }
                        }
                    } else item("overview") {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            if (selection == "map") TextButton(enabled = !busy && !active, onClick = { editingGoal = true }) {
                                Text(stringResource(R.string.pmap_edit_goal))
                            }
                            MapOverview(project, tasks, roles, selection, selectedRole, navigate)
                        }
                    }
                    item("return_to_map") {
                        TextButton(onClick = {
                            // Explicit map navigation clears internal history, but retains the chosen node anchor.
                            history = arrayListOf("map")
                            scope.launch { focusMap() }
                        }, modifier = Modifier.heightIn(min = 48.dp).testTag("pmap_return_map")) {
                            Text(stringResource(R.string.pmap_return_map))
                        }
                    }
                }
            }
        }
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            LazyColumn(state = mapListState, modifier = Modifier.weight(1.55f), contentPadding = PaddingValues(bottom = Spacing.xl)) {
                item("canvas") { map() }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
        } else detail()
    }
}

@Composable
private fun ProjectMapCanvas(project: ProjectPlan, tasks: List<ProjectTask>, roles: List<String>, selection: String,
    accepted: Int, total: Int, nodes: MutableMap<String, Pair<BringIntoViewRequester, FocusRequester>>,
    navigate: (String) -> Unit) {
    Surface(color = MaterialTheme.semanticColors.mutedSurface, shape = RoundedCornerShape(Radius.xl),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(Spacing.sm)) {
            val stacked = (maxWidth - Spacing.sm) / 2 < (120 * LocalDensity.current.fontScale).dp
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(project.overallGoal.ifBlank { project.purpose }.ifBlank { project.requirements }
                    .ifBlank { stringResource(R.string.pmap_no_description) }, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 320.dp)) {
                    MapNode("map", project.title, stringResource(R.string.pmap_counts, accepted, total,
                        tasks.count { !it.isRequirementBranch && it.status == ProjectTaskStatus.BLOCKED }), selection == "map",
                        false, 0, nodes, navigate)
                }
                val wire = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.fillMaxWidth().height(Spacing.xl)) {
                    drawLine(wire, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height / 2), 1.dp.toPx())
                    drawLine(wire, Offset(size.width / 4, size.height / 2), Offset(size.width * 3 / 4, size.height / 2), 1.dp.toPx())
                    for (x in listOf(size.width / 4, size.width * 3 / 4))
                        drawLine(wire, Offset(x, size.height / 2), Offset(x, size.height), 1.dp.toPx())
                }
                val team: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        MapNode("team", stringResource(R.string.pmap_team), stringResource(R.string.pmap_role_count, roles.size),
                            selection == "team", false, 1, nodes, navigate)
                        if (roles.isEmpty()) Text(stringResource(R.string.pmap_no_roles), Modifier.padding(Spacing.sm))
                        roles.forEach { role ->
                            val chosenTask = tasks.firstOrNull { "task:${it.id}" == selection }
                            val related = chosenTask?.let { ProjectMindMapModels.related(it, role) } == true
                            val relation = chosenTask?.let { MapRoleRelation(it, role) }
                            MapNode("role:$role", role, if (related) relation.orEmpty() else
                                stringResource(R.string.pmap_role_tasks, tasks.count { it.owner == role }, tasks.count { role in it.members }),
                                selection == "role:$role", related, 3, nodes, navigate)
                        }
                    }
                }
                val work: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        MapNode("work", stringResource(R.string.pmap_work), stringResource(R.string.pmap_task_count, total),
                            selection == "work", false, 2, nodes, navigate)
                        if (tasks.isEmpty()) Text(stringResource(R.string.pmap_no_tasks), Modifier.padding(Spacing.sm))
                        tasks.forEach { task ->
                            val role = selection.removePrefix("role:").takeIf { selection.startsWith("role:") }
                            val related = role != null && ProjectMindMapModels.related(task, role)
                            val status = stringResource(mapTaskStatusResource(task))
                            val sub = buildList {
                                add(status)
                                if (task.owner.isNotBlank()) add(task.owner)
                                if (related) add(MapRoleRelation(task, role!!))
                            }.joinToString(" · ")
                            MapNode("task:${task.id}", task.title, sub, selection == "task:${task.id}", related, 3, nodes, navigate)
                        }
                    }
                }
                if (stacked) { team(); work() }
                else Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.weight(1f)) { team() }; Box(Modifier.weight(1f)) { work() }
                }
                Text(stringResource(R.string.pmap_legend), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun MapNode(id: String, title: String, subtitle: String, selected: Boolean, related: Boolean, tone: Int,
    nodes: MutableMap<String, Pair<BringIntoViewRequester, FocusRequester>>, onClick: (String) -> Unit) {
    val bring = remember(id) { BringIntoViewRequester() }
    val focus = remember(id) { FocusRequester() }
    DisposableEffect(id) { nodes[id] = bring to focus; onDispose { nodes.remove(id) } }
    val colors = MaterialTheme.colorScheme
    val radius = Radius.mdLarge
    val shape = RoundedCornerShape(radius)
    val fill = when { selected -> colors.primaryContainer; tone == 0 -> colors.primary
        tone == 1 -> colors.secondaryContainer; tone == 2 -> colors.primaryContainer; else -> colors.surface }
    val foreground = when { selected -> colors.onPrimaryContainer; tone == 0 -> colors.onPrimary
        tone == 1 -> colors.onSecondaryContainer; tone == 2 -> colors.onPrimaryContainer; else -> colors.onSurface }
    val outline = colors.primary
    val wire = colors.outlineVariant
    val grouping = if (tone == 3) Modifier.padding(start = Spacing.sm).drawBehind {
        val start = -Spacing.sm.toPx() / 2
        drawLine(wire, Offset(start, 0f), Offset(start, size.height), 1.dp.toPx())
        drawLine(wire, Offset(start, size.height / 2), Offset(0f, size.height / 2), 1.dp.toPx())
    } else Modifier
    val dash = if (related && !selected) Modifier.drawWithContent {
        drawContent()
        drawRoundRect(outline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    } else Modifier
    Surface(onClick = { onClick(id) }, color = fill, contentColor = foreground, shape = shape,
        border = if (related && !selected) null else BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().then(grouping).heightIn(min = 48.dp).bringIntoViewRequester(bring)
            .focusRequester(focus).then(dash).semantics { this.selected = selected }.testTag("pmap_node_$id")) {
        Column(Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun MapRoleRelation(task: ProjectTask, role: String): String = buildList {
    if (task.owner == role) add(stringResource(R.string.pmap_owner))
    if (role in task.members) add(stringResource(R.string.pmap_collaboration))
    if (task.processMembers.values.any { it.role == role }) add(stringResource(R.string.pmap_execution_role))
}.joinToString(" / ")

@Composable
private fun MapOverview(project: ProjectPlan, tasks: List<ProjectTask>, roles: List<String>, selection: String,
    role: String?, navigate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (selection == "map") {
            MapField(R.string.pmap_goal, project.overallGoal.ifBlank { project.purpose }.ifBlank { project.requirements }
                .ifBlank { stringResource(R.string.pmap_no_description) })
            MapField(R.string.pmap_acceptance, project.goalAcceptance.ifBlank { stringResource(R.string.pmap_no_acceptance) })
        }
        if (selection == "team" || role != null) {
            MapField(R.string.pmap_team, project.executionTeam?.takeIf { it.isNotBlank() && it != "auto" }
                ?: stringResource(R.string.pmap_team_unknown))
        }
        if (selection == "team") {
            if (roles.isEmpty()) Text(stringResource(R.string.pmap_no_roles))
            roles.forEach { MapLink(it) { navigate("role:$it") } }
        } else {
            val associated = if (role == null) tasks else tasks.filter { ProjectMindMapModels.related(it, role) }
            if (selection == "work") {
                Text(stringResource(R.string.pmap_counts, tasks.count { ProjectMindMapModels.accepted(it) },
                    tasks.count { !it.isRequirementBranch }, tasks.count { !it.isRequirementBranch && it.status == ProjectTaskStatus.BLOCKED }))
                if (tasks.none { !it.isRequirementBranch }) Text(stringResource(R.string.pmap_no_execution_tasks))
                ProjectTaskStatus.entries.forEach { status ->
                    val matching = tasks.filter { !it.isRequirementBranch && it.status == status }
                    matching.firstOrNull()?.let { sample -> Text(stringResource(R.string.pmap_status_count,
                        stringResource(mapTaskStatusResource(sample)), matching.size)) }
                }
            }
            if (associated.isEmpty()) Text(stringResource(R.string.pmap_no_tasks))
            associated.sortedBy { if (role != null && it.owner != role && role !in it.members) 1 else 0 }.forEach { task ->
                if (role != null) Text(stringResource(if (task.owner == role || role in task.members)
                    R.string.pmap_current_association else R.string.pmap_history_association), style = MaterialTheme.typography.labelLarge)
                MapLink(task.title + " · " + stringResource(mapTaskStatusResource(task)) +
                    if (role != null) " · " + MapRoleRelation(task, role) else "") { navigate("task:${task.id}") }
                if (role != null) task.processMembers.values.filter { it.role == role }.sortedBy { it.ordinal }.forEach { run ->
                    Text(stringResource(R.string.pmap_attempt_number, run.ordinal), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.pmap_run_role_info, run.requirementsVersion,
                        if (run.outcome == "UNKNOWN") stringResource(R.string.pmap_execution_unconfirmed) else run.outcome, run.reason),
                        style = MaterialTheme.typography.bodyMedium)
                    if (run.requirementsVersion != task.requirementsVersion) Text(stringResource(R.string.pmap_historical_execution))
                }
            }
        }
    }
}
