package com.aharou.feature.agent.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.feature.agent.domain.model.ChatSession
import com.aharou.feature.agent.presentation.component.WorkspaceSessionBrowser
import com.aharou.feature.workspace.domain.model.Workspace
import com.aharou.feature.workspace.presentation.WorkspaceViewModel
import io.mockk.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectTaskConversationTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectSessionBrowserTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Fixture {
        val a = Workspace("A", "/A")
        val b = Workspace("B", "/B")
        val current = MutableStateFlow<Workspace?>(a)
        val available = MutableStateFlow(listOf(a, b))
        val sessions = MutableStateFlow(mapOf(
            "/A" to listOf(ChatSession("old", "Old ordinary", 1, 1, workspacePath = "/A")),
            "/B" to listOf(ChatSession("internal", "Project private", 1, 1, workspacePath = "/B"))))
        val state = MutableStateFlow(ProjectTaskUiState(workspace = "/A", loading = false,
            indexedSessionWorkspaces = setOf("/A", "/B"), projectSessionIds = setOf("internal")))
        val ws = mockk<WorkspaceViewModel>()
        val agent = mockk<AIAgentViewModel>(relaxed = true)
        val projects = mockk<ProjectTaskViewModel>()
        var opened = 0

        init {
            every { ws.current } returns current
            every { ws.workspaces } returns available
            every { ws.selectWorkspace(any()) } answers {
                current.value = available.value.single { it.name == firstArg<String>() }
                Job().apply { complete() }
            }
            every { agent.sessionsByWorkspace } returns sessions
            every { agent.currentSessionId } returns MutableStateFlow<String?>(null)
            every { agent.agentStates } returns MutableStateFlow(emptyMap())
            every { agent.activeAgentSessions } returns MutableStateFlow(emptySet())
            every { agent.awaitingPermissionSessionIds } returns MutableStateFlow(emptySet())
            every { agent.newSession() } returns Job().apply { complete() }
            every { projects.state } returns state
        }
    }

    private fun mount(fixture: Fixture, newChat: Boolean = false) {
        compose.setContent { MaterialTheme {
            WorkspaceSessionBrowser(fixture.agent, fixture.projects, fixture.ws, false, {}, {}, {},
                onOpenChat = { fixture.opened++ }, newSessionRequested = newChat)
        } }
    }

    private fun openPicker() {
        compose.onNodeWithTag("create_project_or_session").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_new_session)).performClick()
    }

    @Test fun groupsSurviveAnotherWorkspaceFailureWithoutExposingProjectSessions() {
        val fixture = Fixture()
        mount(fixture)
        compose.onNodeWithText("Old ordinary", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Project private", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            fixture.current.value = fixture.b
            fixture.state.value = fixture.state.value.copy(workspace = "/B",
                indexedSessionWorkspaces = setOf("/A"), failedSessionWorkspaces = setOf("/B"))
            fixture.available.value += Workspace("C", "/C")
            fixture.sessions.value = fixture.sessions.value + mapOf(
                "/removed" to listOf(ChatSession("orphan", "Removed private", 1, 1, workspacePath = "/removed")),
                "/C" to listOf(ChatSession("not-filtered", "Unindexed private", 1, 1, workspacePath = "/C")))
        }
        compose.onNodeWithText("Old ordinary", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("Project private", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Unindexed private", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("session_list").performScrollToNode(hasText(compose.activity.getString(R.string.project_session_index_failed)))
        compose.onNodeWithTag("session_list").performScrollToNode(hasText(compose.activity.getString(R.string.project_session_index_loading)))
        compose.onNodeWithTag("session_list").performScrollToNode(hasText(compose.activity.getString(R.string.workspace_external_unavailable_hint)))
        compose.onNodeWithText("Removed private", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            verify(exactly = 1) { fixture.agent.selectSession("old") }
            assertEquals(1, fixture.opened)
        }
    }

    @Test fun projectCreationStillOpensProjectFormInsteadOfSessionPicker() {
        val fixture = Fixture()
        mount(fixture)
        compose.onNodeWithTag("create_project_or_session").performClick()
        compose.onNodeWithTag("create_project").performClick()
        compose.onNodeWithTag("save_project_drafts").assertExists()
        compose.onNodeWithTag("new_session_use_workspace").assertDoesNotExist()
    }

    @Test fun unselectedWorkspaceUsesCurrentDefaultAndExternalNewChatOpensPicker() {
        val fixture = Fixture()
        mount(fixture, newChat = true)
        compose.onNodeWithTag("new_session_workspace_/A").assertIsNotSelected()
        compose.onNodeWithTag("new_session_use_workspace").performClick()
        compose.runOnIdle {
            verifyOrder { fixture.ws.selectWorkspace("A"); fixture.agent.setWorkspace("/A"); fixture.agent.newSession() }
            assertEquals(1, fixture.opened)
        }
    }

    @Test fun selectedWorkspaceIsUsedAndBusyCreationCannotBeSubmittedTwice() {
        val fixture = Fixture()
        val pending = Job()
        every { fixture.agent.newSession() } returns pending
        mount(fixture)
        openPicker()
        compose.onNodeWithTag("new_session_workspace_/B").performClick().assertIsSelected()
        compose.onNodeWithTag("new_session_use_workspace").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("new_session_create_workspace").assertIsNotEnabled()
        compose.runOnIdle {
            verify(exactly = 1) { fixture.agent.newSession() }
            assertEquals(0, fixture.opened)
            pending.complete()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            verifyOrder { fixture.ws.selectWorkspace("B"); fixture.agent.setWorkspace("/B"); fixture.agent.newSession() }
            assertEquals(1, fixture.opened)
        }
    }

    @Test fun creatingWorkspaceCreatesSessionOnlyAfterWorkspaceSelection() {
        val fixture = Fixture()
        val created = Workspace("New", "/New")
        every { fixture.ws.createWorkspace("New", any()) } answers {
            fixture.available.value += created
            secondArg<(Workspace?) -> Unit>()(created)
            Job().apply { complete() }
        }
        mount(fixture)
        openPicker()
        compose.onNodeWithTag("new_session_workspace_name").performTextInput("New")
        compose.onNodeWithTag("new_session_create_workspace").performClick()
        compose.runOnIdle {
            verifyOrder { fixture.ws.createWorkspace("New", any()); fixture.ws.selectWorkspace("New")
                fixture.agent.setWorkspace("/New"); fixture.agent.newSession() }
            assertEquals(1, fixture.opened)
        }
    }

    @Test fun failedWorkspaceCreationKeepsPickerAndDoesNotCreateSession() {
        val fixture = Fixture()
        every { fixture.ws.createWorkspace("Duplicate", any()) } answers {
            secondArg<(Workspace?) -> Unit>()(null)
            Job().apply { complete() }
        }
        mount(fixture)
        openPicker()
        compose.onNodeWithTag("new_session_workspace_name").performTextInput("Duplicate")
        compose.onNodeWithTag("new_session_create_workspace").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.session_workspace_operation_failed)).assertExists()
        compose.onNodeWithTag("new_session_use_workspace").assertIsEnabled()
        compose.runOnIdle {
            verify(exactly = 0) { fixture.agent.newSession() }
            assertEquals(0, fixture.opened)
        }
    }
}
