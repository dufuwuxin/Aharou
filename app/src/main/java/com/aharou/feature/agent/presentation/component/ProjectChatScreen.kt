package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.feature.agent.presentation.AIAgentViewModel
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.ProjectTaskViewModel
import com.aharou.feature.settings.presentation.SettingsViewModel
import com.aharou.feature.workspace.presentation.WorkspaceViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronLeft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

internal data class ProjectChatBinding(
    val sessionId: String?,
    val rootConversation: @Composable () -> Unit,
    val body: @Composable (() -> Unit) -> Unit
)

/** Ordinary unbound chats retain their original list; membership never creates a project. */
@Composable
internal fun rememberProjectChatBinding(agent: AIAgentViewModel, projects: ProjectTaskViewModel,
    onBack: () -> Unit, onToolDetail: (AgentUIMessage) -> Unit, projectId: String? = null): ProjectChatBinding? {
    val state by projects.state.collectAsStateWithLifecycle()
    val currentSession by agent.currentSessionId.collectAsStateWithLifecycle()
    val messages by agent.messagesState.collectAsStateWithLifecycle()
    val rootSessions by agent.sessions.collectAsStateWithLifecycle()
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
    var requirementBranchId by rememberSaveable(id, workspace) { mutableStateOf<String?>(null) }
    val selectedBranch = state.tasks.singleOrNull { it.id == requirementBranchId && it.projectId == id &&
        it.workspace == workspace && it.isRequirementBranch }
    val chooseBranch: (String?) -> Unit = { target ->
        if (requirementBranchId != target) { requirementBranchId = target; sessionId = null; failed = false }
    }
    LaunchedEffect(id, workspace, project != null, state.workspace, requirementBranchId) {
        if (project == null || workspace.isBlank() || state.workspace != workspace) return@LaunchedEffect
        val startingSession = agent.currentSessionId.value
        try {
            val requestedBranch = requirementBranchId
            val target = if (requestedBranch == null) projects.ensureProjectSession(id, workspace)
                else projects.ensureBranchSession(requestedBranch, workspace)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val current = agent.currentSessionId.value
            val stillBound = current == startingSession || current in project.flowSessionIds ||
                state.tasks.any { it.projectId == id && (current in it.sessionIds || current in it.processSessionIds) }
            if (stillBound && projects.state.value.workspace == workspace && requirementBranchId == requestedBranch) {
                sessionId = target; agent.selectSession(target)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val ready = sessionId != null && currentSession == sessionId && messages.loaded &&
        messages.sessionId == sessionId && selectedSession?.id == sessionId && selectedSession?.parentId == null &&
        selectedWorkspace == workspace && state.workspace == workspace
    val rootConversation: @Composable () -> Unit = {
        val records = rootSessions.filter { it.id in project?.flowSessionIds.orEmpty() && it.parentId == null && it.workspacePath == workspace }
            .sortedByDescending { it.updatedAt }.map { it.id }
        key(id, workspace) {
            val root = sessionId?.takeIf { requirementBranchId == null } ?: records.firstOrNull()
            if (root == null) Text("发送项目需求后显示实时过程")
            else Box(Modifier.testTag("project_root_record_$root")) { ProjectProgressConversation(root, agent) { ProjectRootConversation(records, agent, onToolDetail) } }
        }
    }
    return ProjectChatBinding(sessionId.takeIf { ready }, rootConversation) { collapse ->
        if (project != null) ProjectTaskWorkbench(id, projects, onBack, onContentDrag = collapse,
            requirementBranchId = requirementBranchId, onSelectRequirementBranch = chooseBranch,
            branchSessionId = sessionId.takeIf { requirementBranchId != null },
            rootConversation = rootConversation, conversation = { _, record -> key(record) {
                ProjectProgressConversation(record, agent) { ProjectTaskConversation(record, agent, onToolDetail) }
            } })
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
    onBack: () -> Unit, onToolDetail: (AgentUIMessage) -> Unit,
    onNavigateToTerminal: () -> Unit, onNavigateToGit: () -> Unit, onNavigateToBrowser: () -> Unit,
    onNavigateToSandbox: () -> Unit, onNavigateToShared: () -> Unit, onNavigateToObserve: () -> Unit) {
    val state by projectViewModel.state.collectAsStateWithLifecycle()
    val project = state.projects.singleOrNull { it.id == projectId }
    LaunchedEffect(projectId, project?.workspace) {
        if (project != null && state.workspace != project.workspace) projectViewModel.openProject(projectId) {}
    }
    val ready = project != null && state.workspace == project.workspace && !state.loading
    var adding by rememberSaveable(projectId, project?.workspace) { mutableStateOf(false) }
    var requirement by rememberSaveable(projectId, project?.workspace) { mutableStateOf("") }
    var submitted by rememberSaveable(projectId, project?.workspace) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.appendSucceededRequest) {
        if (submitted != null && submitted == state.appendSucceededRequest) {
            adding = false
            requirement = ""
            submitted = null
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("project_route_back")) {
                Icon(FeatherIcons.ChevronLeft, stringResource(R.string.project_back))
            }
            Text(project?.title ?: stringResource(R.string.pmap_title), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ready) TextButton(enabled = !state.busy && projectId !in state.activeProjectIds && projectId !in state.planningProjectIds,
                modifier = Modifier.testTag("project_route_add_requirement"), onClick = { adding = true }) {
                Text(stringResource(R.string.pmap_add_requirements))
            }
        }
        if (project == null || !ready) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        state.error != null -> {
                            Text(stringResource(R.string.pmap_project_error, state.error.orEmpty()))
                            if (project != null) OutlinedButton(onClick = { projectViewModel.openProject(projectId) {} }) {
                                Text(stringResource(R.string.pmap_reload))
                            }
                        }
                        state.loading || project != null -> CircularProgressIndicator()
                        else -> Text(stringResource(R.string.pmap_unavailable))
                    }
                }
            }
        } else key(project.workspace, projectId) {
            val binding = checkNotNull(rememberProjectChatBinding(agentViewModel, projectViewModel, onBack, onToolDetail, projectId))
            AIChatPanel(viewModel = agentViewModel, settingsViewModel = settingsViewModel, workspaceViewModel = workspaceViewModel,
                bodyOverride = binding.body, floatingComposer = true, composerSessionId = binding.sessionId,
                projectConversation = binding.rootConversation, onNavigateToTerminal = onNavigateToTerminal,
                onNavigateToGit = onNavigateToGit, onNavigateToBrowser = onNavigateToBrowser,
                onNavigateToSandbox = onNavigateToSandbox, onNavigateToShared = onNavigateToShared,
                onNavigateToObserve = onNavigateToObserve, modifier = Modifier.weight(1f))
            if (adding) Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false,
                dismissOnBackPress = false, dismissOnClickOutside = false)) {
                Surface(Modifier.fillMaxSize()) {
                    ProjectRequirementEditor(projectId, state, projectViewModel, requirement,
                        onText = { requirement = it }, onSubmit = { submitted = it },
                        onDismiss = { projectViewModel.dismissRequirementPreview(); adding = false })
                }
            }
        }
    }
}
