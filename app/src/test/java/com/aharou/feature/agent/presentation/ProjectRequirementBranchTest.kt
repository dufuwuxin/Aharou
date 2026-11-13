package com.aharou.feature.agent.presentation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.aharou.feature.agent.domain.prompt.InjectedContextStore
import com.aharou.feature.agent.domain.steward.StewardController
import com.aharou.feature.agent.domain.steward.StewardSettingsStore
import com.aharou.feature.settings.presentation.ScheduledTasksViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.feature.agent.domain.model.ChatSession
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.presentation.component.ProjectChatBinding
import com.aharou.feature.agent.presentation.component.rememberProjectChatBinding
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectTaskConversationTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectRequirementBranchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realWorkbenchClickCreatesOnlyAnEmptyBranchAndChangesTheExistingBindingThenReturnsToMain() {
        val project = ProjectPlan(id = "p", workspace = "w", title = "Project", requirements = "", purpose = "purpose",
            workspaceOwnership = "owned", flowSessionIds = listOf("main"))
        val state = MutableStateFlow(ProjectTaskUiState(workspace = "w", loading = false, projects = listOf(project)))
        val projects = mockk<ProjectTaskViewModel>(relaxed = true)
        every { projects.state } returns state
        coEvery { projects.ensureProjectSession("p", "w") } returns "main"
        coEvery { projects.ensureBranchSession("branch", "w") } returns "discussion"
        val agent = mockk<AIAgentViewModel>(relaxed = true)
        val main = ChatSession("main", "Main", 1, 1, workspacePath = "w")
        val discussion = ChatSession("discussion", "Branch", 1, 1, workspacePath = "w")
        val current = MutableStateFlow<String?>("main")
        val selected = MutableStateFlow<ChatSession?>(main)
        val messages = MutableStateFlow(ChatMessagesState("main", emptyList(), true))
        val sessions = MutableStateFlow(listOf(main, discussion))
        every { agent.currentSessionId } returns current
        every { agent.currentSessionState } returns selected
        every { agent.currentSessionWorkspace } returns MutableStateFlow("w")
        every { agent.messagesState } returns messages
        every { agent.sessions } returns sessions
        every { agent.activeAgentSessions } returns MutableStateFlow(emptySet())
        every { agent.processMessages(any(), any()) } returns MutableStateFlow(emptyList())
        every { agent.processText(any()) } returns MutableStateFlow(null)
        every { agent.processReasoning(any()) } returns MutableStateFlow(null)
        every { agent.processTools(any()) } returns MutableStateFlow(emptyList())
        every { agent.selectSession(any()) } answers {
            val id = firstArg<String>()
            current.value = id
            selected.value = if (id == "main") main else discussion
            messages.value = ChatMessagesState(id, emptyList(), true)
        }
        every { projects.createRequirementBranch("p", "Named branch", null, any()) } answers {
            val branch = ProjectTask(id = "branch", workspace = "w", projectId = "p", title = "Named branch",
                requirement = "", sessionIds = listOf("discussion"), isRequirementBranch = true)
            state.value = state.value.copy(tasks = listOf(branch))
            arg<(String) -> Unit>(3)(branch.id)
        }
        val panelOwner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore().apply {
                put("androidx.lifecycle.ViewModelProvider.DefaultKey:${ScheduledTasksViewModel::class.java.canonicalName}",
                    mockk<ScheduledTasksViewModel>(relaxed = true).also { scheduled ->
                        every { scheduled.tasks } returns MutableStateFlow(emptyList())
                        every { scheduled.sessions } returns MutableStateFlow(emptyList())
                    })
                put("androidx.lifecycle.ViewModelProvider.DefaultKey:${StewardPanelViewModel::class.java.canonicalName}",
                    StewardPanelViewModel(StewardController(StewardSettingsStore.Noop, mockk()),
                        StewardSettingsStore.Noop, mockk()))
            }
        }
        var binding: ProjectChatBinding? = null
        compose.setContent {
            MaterialTheme {
                binding = rememberProjectChatBinding(agent, projects, {}, {}, "p")
                Column {
                    Text(binding?.sessionId.orEmpty(), Modifier.testTag("actual_binding_session"))
                    CompositionLocalProvider(LocalViewModelStoreOwner provides panelOwner) {
                        com.aharou.feature.agent.presentation.component.ProjectChatMenuHost(binding?.sessionId,
                            {}, {}, {}, {}, {}, {}, null, 0, 0) { binding?.body?.invoke({}) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("actual_binding_session").assertTextEquals("main")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.project_view_actions)).performClick()
        compose.onNodeWithTag("project_add_task").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.project_add_task_submit)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Named branch")
        compose.onNodeWithText(compose.activity.getString(R.string.project_add_task_submit)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("actual_binding_session").assertTextEquals("discussion")
        compose.onNodeWithTag("project_branch_composer").assertExists()
        compose.onNodeWithTag("map_process_sheet").assertDoesNotExist()
        compose.onNodeWithTag("project_branch_inline").assertExists()
        compose.runOnIdle {
            assertEquals(1, state.value.tasks.size)
            assertTrue(state.value.tasks.single().isRequirementBranch)
            assertEquals("", state.value.tasks.single().requirement)
            assertEquals("discussion", binding!!.sessionId)
        }
        verify(exactly = 1) { projects.createRequirementBranch("p", "Named branch", null, any()) }
        verify(exactly = 0) { projects.addTaskByDescription(any(), any(), any()) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.project_view_actions)).performClick()
        compose.onNodeWithTag("project_flow_start").assertIsEnabled().performClick()
        verify(exactly = 1) { projects.startRequirementBranch("p", "branch") }
        compose.onNodeWithTag("project_branch_delete").performClick()
        compose.onNodeWithTag("project_branch_delete_confirm").assertExists()
        compose.onNodeWithText(compose.activity.getString(android.R.string.cancel)).performClick()
        compose.onNodeWithTag("project_branch_delete_confirm").assertDoesNotExist()
        verify(exactly = 0) { projects.deleteRequirementBranch(any(), any(), any()) }
        compose.runOnIdle { assertEquals(1, state.value.tasks.size) }
        // Unrelated root activity must not steal the explicitly selected branch/composer.
        compose.runOnIdle { sessions.value = listOf(main.copy(updatedAt = 999), discussion) }
        compose.waitForIdle()
        compose.onNodeWithTag("actual_binding_session").assertTextEquals("discussion")
        compose.runOnIdle {
            state.value = state.value.copy(tasks = state.value.tasks + ProjectTask(id = "child", workspace = "w", projectId = "p",
                title = "Planned child", requirement = "Build child", parentId = "branch"))
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.project_view_actions)).performClick()
        compose.onNodeWithTag("project_mode_toggle").performClick()
        compose.onNodeWithTag("project_tree_body").performScrollToNode(hasTestTag("task_node_child"))
        compose.onNodeWithTag("task_node_child").assertExists()
        compose.onNodeWithTag("project_main_conversation").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("main", binding?.sessionId) }
        compose.onNodeWithTag("project_main_reader").assertExists()
        verify(atLeast = 1) { agent.processMessages("main", any()) }
        compose.onNodeWithText(compose.activity.getString(R.string.project_back)).performClick()
        compose.onNodeWithTag("project_main_reader").assertDoesNotExist()
        // Re-enter a branch, then open the root through the real tree callback.
        compose.onNodeWithTag("project_tree_body").performScrollToNode(hasTestTag("task_node_branch"))
        compose.onNodeWithTag("task_node_branch").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("actual_binding_session").assertTextEquals("discussion")
        compose.onNodeWithTag("project_tree_body").performScrollToNode(hasTestTag("task_node_root"))
        compose.onNodeWithTag("task_node_root").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("project_root_record_main").assertExists()
        compose.onNodeWithTag("actual_binding_session").assertTextEquals("main")
        compose.onNodeWithTag("project_tree_body").performScrollToNode(hasTestTag("task_node_branch"))
        compose.onNodeWithTag("task_node_branch").performClick()
        compose.waitForIdle()
        var deletionFinished: (() -> Unit)? = null
        every { projects.deleteRequirementBranch("p", "branch", any()) } answers {
            deletionFinished = arg<() -> Unit>(2)
        }
        compose.onNodeWithTag("project_branch_delete").performClick()
        compose.onNodeWithTag("project_branch_delete_confirm").performClick()
        compose.runOnIdle {
            state.value = state.value.copy(tasks = state.value.tasks + ProjectTask(id = "sibling", workspace = "w",
                projectId = "p", title = "Sibling", requirement = "", sessionIds = listOf("sibling-session"), isRequirementBranch = true))
        }
        coEvery { projects.ensureBranchSession("sibling", "w") } returns "sibling-session"
        compose.onNodeWithTag("project_tree_body").performScrollToNode(hasTestTag("task_node_sibling"))
        compose.onNodeWithTag("task_node_sibling").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("project_branch_composer").assertTextEquals(compose.activity.getString(R.string.project_branch_composer, "Sibling"))
        compose.runOnIdle {
            state.value = state.value.copy(tasks = state.value.tasks.filterNot { it.id == "branch" || it.id == "child" })
            requireNotNull(deletionFinished).invoke()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("project_branch_composer").assertTextEquals(compose.activity.getString(R.string.project_branch_composer, "Sibling"))
        compose.onNodeWithTag("project_main_conversation").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("project_main_reader").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.project_back)).performClick()
        compose.onNodeWithTag("actual_binding_session").assertTextEquals("main")
        compose.runOnIdle { assertEquals(listOf("sibling"), state.value.tasks.map { it.id }) }
        verify(exactly = 1) { projects.deleteRequirementBranch("p", "branch", any()) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.project_view_actions)).performClick()
        compose.onNodeWithTag("project_mode_toggle").performClick()
        compose.onNodeWithTag("map_node_project:p").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("map_root_conversation").assertExists()
        compose.onNodeWithTag("project_root_record_main").assertExists()
        compose.runOnIdle { panelOwner.viewModelStore.clear() }
    }

    @Test fun actualPanelInputAndSendKeepQuestionsReadOnlyAndPlanFiveChildrenInTheSelectedBranch() {
        val project = ProjectPlan(id = "p", workspace = "w", title = "Project", requirements = "", purpose = "purpose",
            workspaceOwnership = "owned", flowSessionIds = listOf("main"))
        val branch = ProjectTask(id = "branch", workspace = "w", projectId = "p", title = "Named branch", requirement = "",
            sessionIds = listOf("discussion"), isRequirementBranch = true)
        val ui = MutableStateFlow(ProjectTaskUiState(workspace = "w", loading = false, projects = listOf(project), tasks = listOf(branch)))
        val projects = mockk<ProjectTaskViewModel>(relaxed = true)
        every { projects.state } returns ui
        coEvery { projects.ensureProjectSession("p", "w") } returns "main"
        coEvery { projects.ensureBranchSession("branch", "w") } returns "discussion"
        val agent = mockk<AIAgentViewModel>(relaxed = true)
        val main = ChatSession("main", "Main", 1, 1, workspacePath = "w")
        val discussion = ChatSession("discussion", "Branch", 1, 1, workspacePath = "w")
        val current = MutableStateFlow<String?>("main")
        val selected = MutableStateFlow<ChatSession?>(main)
        val messages = MutableStateFlow(ChatMessagesState("main", emptyList(), true))
        val draft = MutableStateFlow("")
        every { agent.currentSessionId } returns current
        every { agent.currentSessionState } returns selected
        every { agent.currentSessionWorkspace } returns MutableStateFlow("w")
        every { agent.messagesState } returns messages
        every { agent.sessions } returns MutableStateFlow(listOf(main, discussion))
        every { agent.activeAgentSessions } returns MutableStateFlow(emptySet())
        every { agent.agentState } returns MutableStateFlow(AgentUIState.Idle)
        every { agent.contextUsages } returns MutableStateFlow(emptyMap())
        every { agent.runningTool } returns MutableStateFlow(emptyList())
        every { agent.isCompacting } returns MutableStateFlow(false)
        every { agent.retryState } returns MutableStateFlow(null)
        every { agent.keySwitchState } returns MutableStateFlow(null)
        every { agent.streamingText } returns MutableStateFlow(null)
        every { agent.streamingReasoning } returns MutableStateFlow(null)
        every { agent.reasoningTiming } returns MutableStateFlow(null)
        every { agent.preparingTool } returns MutableStateFlow(null)
        every { agent.pendingToolPermission } returns MutableStateFlow(null)
        every { agent.pendingPermissionBatch } returns MutableStateFlow(null)
        every { agent.pendingToolPermissionSessionTitle } returns MutableStateFlow("")
        every { agent.pendingUserQuestion } returns MutableStateFlow(null)
        every { agent.currentSessionTodoItems } returns MutableStateFlow(emptyList())
        every { agent.queuedRequests } returns MutableStateFlow(emptyList())
        every { agent.targetRewindMessageId } returns MutableStateFlow(null)
        every { agent.currentSessionProviderModel } returns MutableStateFlow(null to null)
        every { agent.currentSessionMode } returns MutableStateFlow(com.aharou.feature.agent.domain.model.AgentMode.PLAN)
        every { agent.currentSessionReasoningEffort } returns MutableStateFlow(com.aharou.feature.agent.domain.model.ReasoningEffort.DEFAULT)
        every { agent.slashCommands } returns MutableStateFlow(emptyList())
        every { agent.inputDraft } returns draft
        every { agent.fileMentionState } returns MutableStateFlow(com.aharou.feature.agent.domain.mention.FileMentionState.Hidden)
        every { agent.pendingAttachments } returns MutableStateFlow(emptyList())
        every { agent.pastedTexts } returns MutableStateFlow(emptyList())
        every { agent.toolExpansionOverrides } returns androidx.compose.runtime.mutableStateMapOf()
        every { agent.turnExpansionOverrides } returns androidx.compose.runtime.mutableStateMapOf()
        every { agent.reasoningExpansionOverrides } returns androidx.compose.runtime.mutableStateMapOf()
        every { agent.reasoningDurations } returns androidx.compose.runtime.mutableStateMapOf()
        every { agent.streamingReasoningKey(any()) } returns "reasoning"
        every { agent.turnKeysOf(any()) } returns emptyMap()
        every { agent.llmCallEvents } returns kotlinx.coroutines.flow.MutableSharedFlow()
        every { agent.pendingPlanApproval } returns MutableStateFlow(null)
        every { agent.pendingPlanApprovalSessionTitle } returns MutableStateFlow("")
        every { agent.rewindPreviewFiles } returns MutableStateFlow(0)
        every { agent.rewindUndoRecords } returns MutableStateFlow(emptyList())
        every { agent.rewindConflicts } returns MutableStateFlow(emptyList())
        every { agent.subAgentCardBridge } returns com.aharou.feature.agent.presentation.component.SubAgentCardBridge(
            MutableStateFlow(emptyMap()), {}, {})
        every { agent.processMessages(any(), any()) } returns MutableStateFlow(emptyList())
        every { agent.processText(any()) } returns MutableStateFlow(null)
        every { agent.processReasoning(any()) } returns MutableStateFlow(null)
        every { agent.processTools(any()) } returns MutableStateFlow(emptyList())
        every { agent.expandPastes(any()) } answers { firstArg<String>() }
        every { agent.updateInputDraft(any()) } answers { draft.value = firstArg() }
        every { agent.clearInputDraft() } answers { draft.value = "" }
        every { agent.selectSession(any()) } answers {
            val id = firstArg<String>()
            current.value = id
            selected.value = if (id == "main") main else discussion
            messages.value = ChatMessagesState(id, emptyList(), true)
            draft.value = ""
        }
        val repository = mockk<com.aharou.feature.agent.data.task.ProjectTaskRepository>()
        val stores = MutableStateFlow(mapOf("w" to com.aharou.feature.agent.data.task.ProjectTaskSnapshot(
            tasks = listOf(branch), projects = listOf(project))))
        every { repository.snapshots } returns stores
        coEvery { repository.load("w") } answers { stores.value.getValue("w") }
        coEvery { repository.appendBranchRequirements("branch", "Plan five tasks", "w") } answers {
            val children = (1..5).map { ProjectTask(id = "c$it", workspace = "w", projectId = "p", title = "Child $it",
                requirement = "Work $it", parentId = "branch", constraints = listOf("Keep original data")) }
            stores.value = mapOf("w" to stores.value.getValue("w").copy(tasks = listOf(branch) + children))
            ui.value = ui.value.copy(tasks = listOf(branch) + children)
            com.aharou.feature.agent.data.task.ProjectPlanOutcome(project, false)
        }
        val tool = com.aharou.feature.agent.domain.tool.session.ProjectTaskTool(repository, mockk(relaxed = true))
        every { agent.currentSessionReceipts } returns MutableStateFlow(emptyList())
        val sent = mutableListOf<Pair<String, String?>>()
        every { agent.enqueueAgentRequest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val request = arg<String>(0)
            val target = arg<String?>(8)
            sent += request to target
            // Deterministic model stand-in: only the explicit planning request calls the real scoped tool.
            if (request == "Plan five tasks") {
                assertEquals(request, arg<String>(1))
                assertEquals("discussion", target)
                assertEquals("w", arg<String>(4))
                val result = kotlinx.coroutines.runBlocking {
                    tool.executeWithContext(mapOf("action" to kotlinx.serialization.json.JsonPrimitive("plan"),
                        "requirement" to kotlinx.serialization.json.JsonPrimitive(request)),
                        com.aharou.feature.agent.domain.model.AgentContext(null, null, arg<String>(4), null,
                            sessionId = target, mode = com.aharou.feature.agent.domain.model.AgentMode.PLAN))
                }
                assertTrue(result is com.aharou.feature.agent.domain.tool.ToolResult.Success)
            }
        }
        val panelOwner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore().apply {
                put("androidx.lifecycle.ViewModelProvider.DefaultKey:${InjectedContextViewModel::class.java.canonicalName}",
                    InjectedContextViewModel(InjectedContextStore()))
                put("androidx.lifecycle.ViewModelProvider.DefaultKey:${ScheduledTasksViewModel::class.java.canonicalName}",
                    mockk<ScheduledTasksViewModel>(relaxed = true).also { scheduled ->
                        every { scheduled.tasks } returns MutableStateFlow(emptyList())
                        every { scheduled.sessions } returns MutableStateFlow(emptyList())
                    })
                put("androidx.lifecycle.ViewModelProvider.DefaultKey:${StewardPanelViewModel::class.java.canonicalName}",
                    StewardPanelViewModel(StewardController(StewardSettingsStore.Noop, mockk()),
                        StewardSettingsStore.Noop, mockk()))
            }
        }
        try {
        compose.setContent {
            MaterialTheme {
                val binding = rememberProjectChatBinding(agent, projects, {}, {}, "p")!!
                CompositionLocalProvider(LocalViewModelStoreOwner provides panelOwner) {
                    com.aharou.feature.agent.presentation.component.AIChatPanel(viewModel = agent, bodyOverride = binding.body,
                        floatingComposer = true, composerSessionId = binding.sessionId, projectConversation = binding.rootConversation)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("project_main_conversation").assertDoesNotExist()
        compose.onNodeWithTag("project_context_status").assertExists().performClick()
        compose.onNodeWithTag("session_diagnostics").assertExists()
        compose.onNodeWithTag("diagnostics_tab_PLANNING").performClick()
        compose.onNodeWithTag("diagnostics_tab_CONTEXT").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.session_context_no_snapshot)).assertExists()
        compose.onNodeWithTag("diagnostics_tab_ACTIVITY").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.activity_sheet_empty)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.conversation_map_close)).performClick()
        compose.waitForIdle()
        fun send(text: String) {
            if (compose.onAllNodesWithTag("project_composer_capsule").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("project_composer_capsule").performClick()
            compose.onNode(hasSetTextAction()).performTextInput(text)
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_send)).performClick()
            compose.waitForIdle()
        }
        send("What is this project?")
        compose.runOnIdle { assertEquals(listOf(branch), ui.value.tasks); assertEquals("main", sent.last().second) }
        compose.onNodeWithText("Named branch").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("map_process_sheet").assertDoesNotExist()
        compose.onNodeWithTag("project_branch_inline").assertExists()
        send("Explain this branch")
        compose.runOnIdle { assertEquals(listOf(branch), ui.value.tasks); assertEquals("discussion", sent.last().second) }
        io.mockk.coVerify(exactly = 0) { repository.appendBranchRequirements(any(), any(), any()) }
        send("Plan five tasks")
        compose.runOnIdle {
            assertEquals("discussion", sent.last().second)
            val children = ui.value.tasks.filterNot { it.isRequirementBranch }
            assertEquals(5, children.size)
            assertTrue(children.all { it.parentId == "branch" && it.constraints == listOf("Keep original data") })
        }
        io.mockk.coVerify(exactly = 1) { repository.appendBranchRequirements("branch", "Plan five tasks", "w") }
        io.mockk.coVerify(exactly = 0) { repository.appendRequirements(any(), any(), any()) }
        } finally {
            compose.runOnIdle { panelOwner.viewModelStore.clear() }
        }
    }

    @Test fun actualDetailChildActionCreatesAContainerAndContainerHasNoExecutionOrEditActions() {
        val parent = ProjectTask(id = "parent", workspace = "w", projectId = "p", title = "Parent", requirement = "Existing work")
        val state = MutableStateFlow(ProjectTaskUiState(workspace = "w", loading = false, tasks = listOf(parent)))
        val projects = mockk<ProjectTaskViewModel>(relaxed = true)
        every { projects.state } returns state
        var backs = 0
        every { projects.createRequirementBranch("p", "Child branch", "parent", any()) } answers { arg<(String) -> Unit>(3)("b") }
        compose.setContent { MaterialTheme {
            com.aharou.feature.agent.presentation.component.ProjectTaskDetailScreen("parent", projects, { backs++ }, {},
                runExecution = { _, _, _ -> error("no execution expected") })
        } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.project_more_options)).performClick()
        compose.onNodeWithTag("detail_add_child").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Child branch")
        compose.onNodeWithText(compose.activity.getString(R.string.project_add_task_submit)).performClick()
        compose.runOnIdle { assertEquals(1, backs) }
        verify(exactly = 1) { projects.createRequirementBranch("p", "Child branch", "parent", any()); projects.select("b") }
        verify(exactly = 0) { projects.addTaskByDescription(any(), any(), any()) }
        compose.runOnIdle { state.value = state.value.copy(tasks = listOf(parent.copy(isRequirementBranch = true, requirement = ""))) }
        compose.onNodeWithTag("task_start").assertDoesNotExist()
        compose.onNodeWithTag("task_accept").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.project_more_options)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.project_task_edit)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.project_task_new_execution)).assertDoesNotExist()
    }
}
