package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.feature.agent.presentation.AIAgentViewModel
import com.aharou.feature.agent.presentation.ProjectTaskViewModel
import com.aharou.feature.workspace.presentation.WorkspaceViewModel
import kotlinx.coroutines.launch

@Composable
internal fun WorkspaceSessionBrowser(
    agent: AIAgentViewModel, projects: ProjectTaskViewModel, workspaces: WorkspaceViewModel,
    browserWorkspace: String?, onBrowseWorkspace: (String) -> Unit,
    createRequested: Boolean, onCreateConsumed: () -> Unit,
    onOpenProject: (String) -> Unit, onOpenTask: (String) -> Unit, onOpenChat: () -> Unit
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
    val path = browserWorkspace ?: current?.path.orEmpty()
    LaunchedEffect(current?.path, browserWorkspace) {
        if (browserWorkspace == null) current?.path?.let(onBrowseWorkspace)
    }
    ProjectListScreen(projects, createRequested, onCreateConsumed, onOpenProject, onOpenTask,
        onNewSession = {
            val target = available.firstOrNull { it.path == path && it.available }
            if (target != null) scope.launch {
                workspaces.selectWorkspace(target.name).join()
                if (workspaces.current.value?.path == path) {
                    agent.setWorkspace(path)
                    agent.newSession().join()
                    onOpenChat()
                }
            }
        }, sessionsContent = {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    (available.map { it.path } + sessions.keys + path).distinct().forEach { workspace ->
                        FilterChip(selected = workspace == path, onClick = { onBrowseWorkspace(workspace) },
                            modifier = Modifier.testTag("session_workspace_$workspace"),
                            label = { Text(workspace) })
                    }
                }
                if (path !in projectState.indexedSessionWorkspaces) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(if (path in projectState.failedSessionWorkspaces)
                            R.string.project_session_index_failed else R.string.project_session_index_loading))
                    }
                } else SessionListScreen(
                    sessions = sessions[path].orEmpty().filterNot { it.id in projectState.projectSessionIds },
                    currentSessionId = currentSession, agentStates = agentStates, activeSessionIds = active,
                    awaitingPermissionSessionIds = permissions, workspaceName = path,
                    onSelect = { agent.selectSession(it.id); onOpenChat() },
                    onDelete = { agent.deleteSession(it.id) }, onNewSession = {}, showNewSession = false)
            }
        })
}
