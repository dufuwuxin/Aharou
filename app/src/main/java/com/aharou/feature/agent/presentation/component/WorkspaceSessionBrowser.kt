package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.presentation.AIAgentViewModel
import com.aharou.feature.agent.presentation.ProjectTaskViewModel
import com.aharou.feature.workspace.domain.model.Workspace
import com.aharou.feature.workspace.presentation.WorkspaceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun WorkspaceSessionBrowser(
    agent: AIAgentViewModel,
    projects: ProjectTaskViewModel,
    workspaces: WorkspaceViewModel,
    createRequested: Boolean,
    onCreateConsumed: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenChat: () -> Unit,
    newSessionRequested: Boolean = false,
    onNewSessionConsumed: () -> Unit = {}
) {
    val current by workspaces.current.collectAsStateWithLifecycle()
    val available by workspaces.workspaces.collectAsStateWithLifecycle()
    val sessions by agent.sessionsByWorkspace.collectAsStateWithLifecycle()
    val projectState by projects.state.collectAsStateWithLifecycle()
    val currentSession by agent.currentSessionId.collectAsStateWithLifecycle()
    val agentStates by agent.agentStates.collectAsStateWithLifecycle()
    val active by agent.activeAgentSessions.collectAsStateWithLifecycle()
    val permissions by agent.awaitingPermissionSessionIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showNewSession by remember { mutableStateOf(false) }
    var selectedWorkspace by remember { mutableStateOf("") }
    var newWorkspaceName by remember { mutableStateOf("") }
    var operationError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun openNewSessionPicker() {
        if (saving) return
        selectedWorkspace = ""
        newWorkspaceName = ""
        operationError = false
        showNewSession = true
    }

    fun openSession(workspace: Workspace) {
        saving = true
        operationError = false
        scope.launch {
            try {
                workspaces.selectWorkspace(workspace.name).join()
                if (workspaces.current.value?.path != workspace.path) {
                    operationError = true
                    return@launch
                }
                agent.setWorkspace(workspace.path)
                val creation = agent.newSession()
                creation.join()
                if (creation.isCancelled) {
                    operationError = true
                    return@launch
                }
                showNewSession = false
                onOpenChat()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                operationError = true
            } finally {
                saving = false
            }
        }
    }

    fun createSessionInNewWorkspace() {
        val name = newWorkspaceName.trim()
        if (name.isBlank() || saving) return
        saving = true
        operationError = false
        workspaces.createWorkspace(name) { created ->
            if (created == null) {
                saving = false
                operationError = true
            } else {
                selectedWorkspace = created.path
                newWorkspaceName = ""
                openSession(created)
            }
        }
    }

    val selectableWorkspaces = available.filter { it.available }
    val defaultPath = current?.path.orEmpty().takeIf { path -> selectableWorkspaces.any { it.path == path } }
        ?: selectableWorkspaces.firstOrNull()?.path.orEmpty()
    val usablePaths = selectableWorkspaces.mapTo(mutableSetOf()) { it.path }
    val readyPaths = projectState.indexedSessionWorkspaces.intersect(usablePaths) - projectState.failedSessionWorkspaces
    val visibleGroups = sessions.filterKeys { it in readyPaths }
        .mapValues { (_, items) -> items.filterNot { it.id in projectState.projectSessionIds } }
        .filterValues { it.isNotEmpty() }
    val workspaceStatuses = (sessions.keys + usablePaths).filter { it !in readyPaths }.associateWith { path ->
        stringResource(when {
            path !in usablePaths -> R.string.workspace_external_unavailable_hint
            path in projectState.failedSessionWorkspaces -> R.string.project_session_index_failed
            else -> R.string.project_session_index_loading
        })
    }

    LaunchedEffect(newSessionRequested) {
        if (newSessionRequested) {
            openNewSessionPicker()
            onNewSessionConsumed()
        }
    }

    ProjectListScreen(
        projects,
        createRequested = createRequested,
        onCreateConsumed = onCreateConsumed,
        onOpenProject = onOpenProject,
        onOpenTask = onOpenTask,
        onNewSession = ::openNewSessionPicker,
        sessionsContent = {
            SessionListScreen(
                    sessions = emptyList(),
                    currentSessionId = currentSession,
                    agentStates = agentStates,
                    activeSessionIds = active,
                    awaitingPermissionSessionIds = permissions,
                    workspaceName = "",
                    onSelect = { agent.selectSession(it.id); onOpenChat() },
                    onDelete = { agent.deleteSession(it.id) },
                    onNewSession = ::openNewSessionPicker,
                    showNewSession = false,
                    sessionGroups = visibleGroups,
                    workspaceStatuses = workspaceStatuses,
                    availableWorkspaces = available,
                    onMove = { session, path ->
                        scope.launch {
                            try { agent.bindSessionWorkspace(session.id, path) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { operationError = true }
                        }
                    }
            )
        }
    )

    if (showNewSession) {
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { if (!saving) showNewSession = false },
            title = { Text(stringResource(R.string.session_new_workspace_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.session_new_workspace_default, defaultPath))
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        items(selectableWorkspaces.size, key = { selectableWorkspaces[it].path }) { index ->
                            val workspace = selectableWorkspaces[index]
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = selectedWorkspace == workspace.path, enabled = !saving,
                                    role = Role.RadioButton, onClick = { selectedWorkspace = workspace.path })
                                .testTag("new_session_workspace_${workspace.path}"),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedWorkspace == workspace.path, onClick = null, enabled = !saving)
                                Text(workspace.name, modifier = Modifier.padding(start = Spacing.sm))
                            }
                        }
                    }
                    AppTextField(
                        value = newWorkspaceName,
                        onValueChange = { newWorkspaceName = it },
                        enabled = !saving,
                        label = stringResource(R.string.workspace_name_hint),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_session_workspace_name")
                    )
                    if (operationError) Text(stringResource(R.string.session_workspace_operation_failed))
                }
            },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("new_session_use_workspace"),
                    enabled = !saving && (selectedWorkspace.isNotBlank() || defaultPath.isNotBlank()), onClick = {
                    val target = selectedWorkspace.ifBlank { defaultPath }
                    available.firstOrNull { it.path == target && it.available }?.let(::openSession)
                }) { Text(stringResource(R.string.session_new_workspace_existing)) }
                TextButton(modifier = Modifier.testTag("new_session_create_workspace"),
                    enabled = !saving && newWorkspaceName.isNotBlank(), onClick = ::createSessionInNewWorkspace) {
                    Text(stringResource(R.string.session_new_workspace_create))
                }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { showNewSession = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (operationError && !showNewSession) {
        AlertDialog(
            onDismissRequest = { operationError = false },
            title = { Text(stringResource(R.string.session_workspace_operation_failed)) },
            text = { Text(stringResource(R.string.session_workspace_move_failed)) },
            confirmButton = {
                TextButton(onClick = { operationError = false }) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }
}
