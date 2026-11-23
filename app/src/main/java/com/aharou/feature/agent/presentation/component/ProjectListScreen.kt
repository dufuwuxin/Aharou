package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.task.*
import com.aharou.feature.agent.presentation.ProjectTaskViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Search

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectListScreen(viewModel: ProjectTaskViewModel, createRequested: Boolean,
    onCreateConsumed: () -> Unit, onOpenProject: (String) -> Unit, onOpenTask: (String) -> Unit,
    onNewSession: () -> Unit, sessionsContent: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deletingProject by remember { mutableStateOf<com.aharou.feature.agent.domain.task.ProjectPlan?>(null) }
    var menuProject by remember { mutableStateOf<ProjectPlan?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val projectListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    var createMenu by remember { mutableStateOf(false) }
    var projectSearchOpen by rememberSaveable { mutableStateOf(false) }
    var collapsedProjects by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var projectQuery by rememberSaveable { mutableStateOf("") }
    val visibleProjects = remember(state.projects, projectQuery) {
        val query = projectQuery.trim()
        state.projects.filter { project ->
            query.isEmpty() || listOf(project.title, project.purpose, project.requirements, project.workspace)
                .any { it.contains(query, ignoreCase = true) }
        }
    }
    var name by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var previousProject by rememberSaveable { mutableStateOf<String?>(null) }
    var continuationId by rememberSaveable { mutableStateOf<String?>(null) }
    var unassigned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(createRequested) {
        if (createRequested) {
            creating = true
            onCreateConsumed()
        }
    }
    LaunchedEffect(submitted, state.busy, state.creating, state.createdProjectId, state.selectedProjectId, state.error) {
        if (submitted && !state.creating && !state.busy) {
            if (state.createdProjectId != null && state.createdProjectId != previousProject) {
                creating = false; name = ""; purpose = ""
                if (state.selectedProjectId == state.createdProjectId) state.selectedProjectId?.let(onOpenProject)
            }
            submitted = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = Spacing.lg).padding(top = Spacing.lg)) {
            Text(stringResource(R.string.project_home_heading), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }.testTag("project_home_heading"))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.project_list_section), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                if (!projectSearchOpen) {
                    IconButton(onClick = { projectSearchOpen = true },
                        modifier = Modifier.size(48.dp).testTag("project_search_open")) {
                        Icon(FeatherIcons.Search, contentDescription = stringResource(R.string.project_search_label))
                    }
                }
            }
            if (projectSearchOpen) {
                Row(Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    AppTextField(projectQuery, {
                        projectQuery = it
                        projectListState.requestScrollToItem(0)
                    }, label = stringResource(R.string.project_search_label), singleLine = true,
                        modifier = Modifier.weight(1f).testTag("project_search"))
                    TextButton(onClick = {
                        focusManager.clearFocus()
                        projectQuery = ""
                        projectSearchOpen = false
                        projectListState.requestScrollToItem(0)
                    }, modifier = Modifier.heightIn(min = 48.dp).testTag("project_search_clear")) {
                        Text(stringResource(R.string.common_clear))
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("project_home_list"), state = projectListState,
                verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                state.error?.let { error -> item {
                    Text(stringResource(R.string.project_task_error, projectTaskExecutionReasonLabel(error)),
                        color = MaterialTheme.colorScheme.error)
                } }
                if (projectSearchOpen) item {
                    Text(stringResource(R.string.project_search_count, visibleProjects.size, state.projects.size),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!state.loading && state.projects.isNotEmpty() && visibleProjects.isEmpty()) item {
                    Text(stringResource(R.string.project_search_empty),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("project_search_empty"))
                }
                if (state.projects.isEmpty() && !state.loading) item {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().testTag("project_empty_state")) {
                        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Text(stringResource(R.string.project_empty_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.project_empty_description), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { creating = true },
                                enabled = !state.busy && state.workspace.isNotBlank(),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("project_empty_create")) {
                                Text(stringResource(R.string.project_create))
                            }
                        }
                    }
                }
                items(visibleProjects, key = { it.id }) { project ->
                    Box {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().testTag("project_${project.id}").combinedClickable(
                                onClick = { onOpenProject(project.id) },
                                onLongClickLabel = stringResource(R.string.project_delete),
                                onLongClick = { menuProject = project }
                            )) {
                            val collapsed = project.id in collapsedProjects
                            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(project.title, style = MaterialTheme.typography.titleLarge,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = { collapsedProjects = if (collapsed) collapsedProjects - project.id else collapsedProjects + project.id },
                                        modifier = Modifier.size(48.dp).testTag("project_details_toggle:${project.id}")
                                    ) {
                                        Icon(if (collapsed) FeatherIcons.ChevronRight else FeatherIcons.ChevronDown,
                                            contentDescription = stringResource(
                                                if (collapsed) R.string.project_details_expand else R.string.project_details_collapse,
                                                project.title
                                            ))
                                    }
                                }
                                Text(stringResource(if (project.workspaceOwnership == "owned") R.string.project_workspace_owned else R.string.project_workspace_legacy, project.workspace),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!collapsed) {
                                    project.continuationSourceProjectId?.let { Text(stringResource(R.string.project_workspace_continuation_source, it)) }
                                    if (project.workspaceOwnership == "legacy_shared") TextButton(
                                        onClick = { continuationId = project.id },
                                        enabled = viewModel.canContinueInOwnedWorkspace(project),
                                        modifier = Modifier.testTag("project_continue_owned_${project.id}")) {
                                        Text(stringResource(R.string.project_workspace_continue))
                                    }
                                    if (project.purpose.isNotBlank()) {
                                        Text(stringResource(R.string.project_purpose), style = MaterialTheme.typography.labelMedium)
                                        Text(project.purpose, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (project.requirements.isNotBlank()) Text(project.requirements, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(stringResource(R.string.project_task_count, state.projectTaskCounts[project.id] ?: 0))
                            }
                        }
                        DropdownMenu(expanded = menuProject?.id == project.id, onDismissRequest = { menuProject = null }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.project_delete), color = MaterialTheme.colorScheme.error) },
                                enabled = viewModel.canDeleteProject(project),
                                modifier = Modifier.testTag("delete_project_${project.id}"),
                                onClick = { menuProject = null; deletingProject = project }
                            )
                        }
                    }
                }
                if (state.tasks.any { it.projectId == null }) {
                    item {
                        TextButton(onClick = { unassigned = !unassigned }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.project_unassigned))
                        }
                    }
                    if (unassigned) items(state.tasks.filter { it.projectId == null }, key = { it.id }) { task ->
                        TextButton(onClick = { onOpenTask(task.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(task.title) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
            Text(stringResource(R.string.project_sessions), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            // The existing session browser owns its scrolling list and needs a bounded viewport.
            Box(Modifier.fillMaxWidth().weight(1f)) { sessionsContent() }
        }
        val createLabel = stringResource(R.string.project_create_menu)
        Box(Modifier.align(Alignment.BottomEnd).padding(Spacing.lg)) {
            FloatingActionButton(onClick = { createMenu = true }, shape = CircleShape,
                modifier = Modifier.width(80.dp).height(52.dp).semantics { contentDescription = createLabel }.testTag("create_project_or_session")) {
                Text(stringResource(R.string.project_add_content), style = MaterialTheme.typography.labelLarge)
            }
            DropdownMenu(expanded = createMenu, onDismissRequest = { createMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_new_session)) },
                    enabled = state.workspace.isNotBlank(), onClick = { createMenu = false; onNewSession() })
                DropdownMenuItem(text = { Text(stringResource(R.string.project_create)) },
                    enabled = !state.loading && !state.busy && state.workspace.isNotBlank(),
                    modifier = Modifier.testTag("create_project"),
                    onClick = { createMenu = false; creating = true })
            }
        }
    }
    deletingProject?.let { project ->
        AlertDialog(onDismissRequest = { deletingProject = null },
            title = { Text(stringResource(R.string.project_delete_title, project.title)) },
            text = { Text(stringResource(R.string.project_delete_description)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteProject(project.id); deletingProject = null },
                    enabled = viewModel.canDeleteProject(project),
                    modifier = Modifier.testTag("confirm_delete_project")) {
                    Text(stringResource(R.string.project_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingProject = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    val continuation = state.projects.firstOrNull { it.id == continuationId }
    if (continuation != null) AlertDialog(onDismissRequest = { continuationId = null },
        title = { Text(stringResource(R.string.project_workspace_continue)) },
        text = { Text(stringResource(R.string.project_workspace_continue_notice)) },
        confirmButton = { TextButton(enabled = viewModel.canContinueInOwnedWorkspace(continuation), onClick = {
            continuationId = null
            viewModel.continueInOwnedWorkspace(continuation.id, onOpenProject)
        }) { Text(stringResource(R.string.project_workspace_continue)) } },
        dismissButton = { TextButton(onClick = { continuationId = null }) { Text(stringResource(R.string.project_close)) } })
    val submitting = submitted || state.creating
    val dismissBlocked by rememberUpdatedState(submitting || state.busy)
    if (creating) AdaptiveModalBottomSheet(onDismissRequest = { if (!dismissBlocked) creating = false },
        sheetGesturesEnabled = !dismissBlocked,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !dismissBlocked })) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.project_create), style = MaterialTheme.typography.titleLarge)
            AppTextField(name, { name = it }, label = stringResource(R.string.project_name), singleLine = true,
                enabled = !submitting && !state.busy)
            AppTextField(purpose, { purpose = it }, label = stringResource(R.string.project_purpose), minLines = 5, singleLine = false,
                enabled = !submitting && !state.busy)
            if (submitting) {
                Text(stringResource(R.string.project_creating), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.error?.let { Text(stringResource(R.string.project_task_error, projectTaskExecutionReasonLabel(it)), color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                if (!submitted && !viewModel.state.value.creating) {
                    previousProject = viewModel.state.value.createdProjectId; submitted = true
                    viewModel.createProject(name, purpose)
                }
            }, enabled = !submitting && !state.busy && !state.loading && state.workspace.isNotBlank() && name.isNotBlank() && purpose.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_project_drafts")) {
                Text(stringResource(if (submitting) R.string.project_creating else R.string.project_create))
            }
        }
    }
}
