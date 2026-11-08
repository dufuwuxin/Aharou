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
fun ProjectListScreen(viewModel: ProjectTaskViewModel, createRequested: Boolean,
    onCreateConsumed: () -> Unit, onOpenProject: (String) -> Unit, onOpenTask: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deletingProject by remember { mutableStateOf<com.aharou.feature.agent.domain.task.ProjectPlan?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var requirements by rememberSaveable { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var previousProject by remember { mutableStateOf<String?>(null) }
    var continuationId by rememberSaveable { mutableStateOf<String?>(null) }
    var unassigned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(createRequested) { if (createRequested) { creating = true; onCreateConsumed() } }
    LaunchedEffect(state.busy, state.createdProjectId) {
        if (submitted && !state.busy && state.error == null && state.createdProjectId != null && state.createdProjectId != previousProject && state.selectedProjectId == state.createdProjectId) {
            submitted = false; creating = false; name = ""; requirements = ""
            state.selectedProjectId?.let(onOpenProject)
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
        .padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Button(onClick = { creating = true }, enabled = !state.loading && !state.busy && state.workspace.isNotBlank(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("create_project")) { Text(stringResource(R.string.project_create)) }
        state.error?.let { Text(stringResource(R.string.project_task_error, projectTaskExecutionReasonLabel(it)), color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.project_workspace_continue_notice), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            items(state.projects, key = { it.id }) { project ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenProject(project.id) }) {
                    Column(Modifier.padding(Spacing.md)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(project.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { deletingProject = project }, enabled = viewModel.canDeleteProject(project),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("delete_project_${project.id}")) {
                                Text(stringResource(R.string.project_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Text(stringResource(if (project.workspaceOwnership == "owned") R.string.project_workspace_owned else R.string.project_workspace_legacy, project.workspace))
                        project.continuationSourceProjectId?.let { Text(stringResource(R.string.project_workspace_continuation_source, it)) }
                        if (project.workspaceOwnership == "legacy_shared") TextButton(
                            onClick = { continuationId = project.id },
                            enabled = viewModel.canContinueInOwnedWorkspace(project),
                            modifier = Modifier.testTag("project_continue_owned_${project.id}")) {
                            Text(stringResource(R.string.project_workspace_continue))
                        }
                        Text(project.requirements, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.project_task_count, state.projectTaskCounts[project.id] ?: 0))
                    }
                }
            }
            if (state.tasks.any { it.projectId == null }) {
                item { TextButton(onClick = { unassigned = !unassigned }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.project_unassigned)) } }
                if (unassigned) items(state.tasks.filter { it.projectId == null }, key = { it.id }) { task ->
                    TextButton(onClick = { onOpenTask(task.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(task.title) }
                }
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
    if (creating) AdaptiveModalBottomSheet(onDismissRequest = { if (!state.busy) creating = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val count = remember(name, requirements, state.workspace) {
            if (name.isBlank() || requirements.isBlank()) 0 else ProjectPlanDraft.build(ProjectPlan(workspace = state.workspace, title = name, requirements = requirements)).size
        }
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.project_create), style = MaterialTheme.typography.titleLarge)
            AppTextField(name, { name = it }, label = stringResource(R.string.project_name), singleLine = true)
            AppTextField(requirements, { requirements = it }, label = stringResource(R.string.project_requirements), minLines = 5, singleLine = false)
            Text(stringResource(R.string.project_draft_preview, count))
            state.error?.let { Text(stringResource(R.string.project_task_error, projectTaskExecutionReasonLabel(it)), color = MaterialTheme.colorScheme.error) }
            Button(onClick = { previousProject = state.createdProjectId; submitted = true; viewModel.createProject(name, requirements) },
                enabled = count > 0 && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_project_drafts")) {
                Text(stringResource(R.string.project_create_generate))
            }
        }
    }
}
