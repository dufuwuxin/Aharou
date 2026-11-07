package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.feature.agent.presentation.AIAgentViewModel
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.ProjectTaskViewModel
import com.aharou.feature.settings.presentation.SettingsViewModel
import com.aharou.feature.workspace.presentation.WorkspaceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive

internal data class ProjectChatBinding(val sessionId: String?, val context: String,
    val body: @Composable (() -> Unit) -> Unit)

/** Ordinary unbound chats retain their original list; membership never creates a project. */
@Composable
internal fun rememberProjectChatBinding(agent: AIAgentViewModel, projects: ProjectTaskViewModel,
    onBack: () -> Unit, onToolDetail: (AgentUIMessage) -> Unit, projectId: String? = null): ProjectChatBinding? {
    val state by projects.state.collectAsStateWithLifecycle()
    val currentSession by agent.currentSessionId.collectAsStateWithLifecycle()
    val messages by agent.messagesState.collectAsStateWithLifecycle()
    val selectedSession by agent.currentSessionState.collectAsStateWithLifecycle()
    val selectedWorkspace by agent.currentSessionWorkspace.collectAsStateWithLifecycle()
    val project = state.projects.firstOrNull { candidate ->
        if (projectId != null) candidate.id == projectId else currentSession in candidate.flowSessionIds ||
            state.tasks.any { task -> task.projectId == candidate.id && currentSession in task.sessionIds }
    }
    if (projectId == null && project == null) return null
    val id = projectId ?: project!!.id
    val workspace = project?.workspace.orEmpty()
    var sessionId by remember(id, workspace) { mutableStateOf<String?>(null) }
    var failed by remember(id, workspace) { mutableStateOf(false) }
    LaunchedEffect(id, workspace, project != null, state.workspace) {
        if (project == null || workspace.isBlank() || state.workspace != workspace) return@LaunchedEffect
        try {
            val root = projects.ensureProjectSession(id, workspace)
            if (projects.state.value.workspace == workspace) { sessionId = root; agent.selectSession(root) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val ready = sessionId != null && currentSession == sessionId && messages.loaded &&
        messages.sessionId == sessionId && selectedSession?.id == sessionId && selectedSession?.parentId == null &&
        selectedWorkspace == workspace && state.workspace == workspace
    val context = project?.let {
        "\n\n[Project conversation: project_id=${JsonPrimitive(it.id)}, workspace=${JsonPrimitive(workspace)}. " +
            "Use project_tasks action=outline to inspect task IDs. Only new or changed requirements call action=plan " +
            "with requirement=user request to add tasks, or action=supplement with task_id and requirement to revise an existing branch. " +
            "Questions, progress checks and reports of an already recorded issue do not change requirements; use read-only queries for them. " +
            "Successful requirement writes request background continuation outside PLAN; PLAN only saves. " +
            "Saving is not evidence of execution or acceptance. " +
            "Continue the project workflow without creating a separate project.]"
    }.orEmpty()
    return ProjectChatBinding(sessionId.takeIf { ready }, context) { collapse ->
        if (project != null) ProjectTaskWorkbench(id, projects, onBack, onContentDrag = collapse,
            rootConversation = {
                val root = sessionId
                if (root != null) key(root) { ProjectTaskConversation(root, agent, onToolDetail) }
                else CircularProgressIndicator()
            }, conversation = { _, record -> key(record) { ProjectTaskConversation(record, agent, onToolDetail) } })
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (failed) Text(stringResource(R.string.project_chat_binding_failed), color = MaterialTheme.colorScheme.error)
            else CircularProgressIndicator()
        }
        if (failed && project != null) Text(stringResource(R.string.project_chat_binding_failed), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun ProjectChatScreen(projectId: String, agentViewModel: AIAgentViewModel, projectViewModel: ProjectTaskViewModel,
    settingsViewModel: SettingsViewModel, workspaceViewModel: WorkspaceViewModel,
    onBack: () -> Unit, onOpenDrawer: () -> Unit, onToolDetail: (AgentUIMessage) -> Unit,
    onNavigateToTerminal: () -> Unit, onNavigateToGit: () -> Unit, onNavigateToBrowser: () -> Unit,
    onNavigateToSandbox: () -> Unit, onNavigateToShared: () -> Unit, onNavigateToObserve: () -> Unit) {
    val binding = rememberProjectChatBinding(agentViewModel, projectViewModel, onBack, onToolDetail, projectId)!!
    AIChatPanel(viewModel = agentViewModel, settingsViewModel = settingsViewModel, workspaceViewModel = workspaceViewModel,
        bodyOverride = binding.body, floatingComposer = true, composerSessionId = binding.sessionId, projectChatContext = binding.context,
        onOpenDrawer = onOpenDrawer, onBackToSessions = onBack,
        onNavigateToTerminal = onNavigateToTerminal, onNavigateToGit = onNavigateToGit, onNavigateToBrowser = onNavigateToBrowser,
        onNavigateToSandbox = onNavigateToSandbox, onNavigateToShared = onNavigateToShared, onNavigateToObserve = onNavigateToObserve)
}
