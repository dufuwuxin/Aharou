package com.aharou.feature.agent.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
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
    private fun workspace(path: String) = mockk<Workspace> {
        every { this@mockk.path } returns path
        every { name } returns path
        every { available } returns true
    }

    @Test fun projectActivationKeepsOrdinaryBrowserAndNewSessionUsesDisplayedWorkspace() {
        val a = workspace("/A")
        val b = workspace("/B")
        val current = MutableStateFlow<Workspace?>(a)
        val ws = mockk<WorkspaceViewModel>()
        every { ws.current } returns current
        every { ws.workspaces } returns MutableStateFlow(listOf(a, b))
        every { ws.selectWorkspace("/A") } answers { current.value = a; Job().apply { complete() } }
        val agent = mockk<AIAgentViewModel>(relaxed = true)
        every { agent.sessionsByWorkspace } returns MutableStateFlow(mapOf(
            "/A" to listOf(ChatSession("old", "Old ordinary", 1, 1, workspacePath = "/A")),
            "/B" to listOf(ChatSession("internal", "Project private", 1, 1, workspacePath = "/B"))))
        every { agent.currentSessionId } returns MutableStateFlow<String?>(null)
        every { agent.agentStates } returns MutableStateFlow(emptyMap())
        every { agent.activeAgentSessions } returns MutableStateFlow(emptySet())
        every { agent.awaitingPermissionSessionIds } returns MutableStateFlow(emptySet())
        every { agent.newSession() } returns Job().apply { complete() }
        val projects = mockk<ProjectTaskViewModel>()
        val state = MutableStateFlow(ProjectTaskUiState(workspace = "/A", loading = false,
            indexedSessionWorkspaces = setOf("/A", "/B"), projectSessionIds = setOf("internal")))
        every { projects.state } returns state
        var opened = 0
        compose.setContent { MaterialTheme {
            var browser by remember { mutableStateOf<String?>(null) }
            WorkspaceSessionBrowser(agent, projects, ws, browser, { browser = it }, false, {}, {}, {}, { opened++ })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.project_sessions)).performClick()
        compose.onNodeWithText("Old ordinary", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { current.value = b; state.value = state.value.copy(workspace = "/B") }
        compose.onNodeWithTag("session_workspace_/A").assertIsSelected()
        compose.onNodeWithText("Old ordinary", useUnmergedTree = true).performClick()
        compose.runOnIdle { verify { agent.selectSession("old") }; assertEquals(1, opened) }
        compose.onNodeWithTag("create_project_or_session").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_new_session)).performClick()
        compose.runOnIdle {
            verifyOrder { ws.selectWorkspace("/A"); agent.setWorkspace("/A"); agent.newSession() }
            assertEquals("/A", current.value?.path)
            assertEquals(2, opened)
        }
        compose.onNodeWithTag("session_workspace_/B").performClick()
        compose.onNodeWithText("Project private", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(indexedSessionWorkspaces = setOf("/A")) }
        compose.onNodeWithText(compose.activity.getString(R.string.project_session_index_loading)).assertIsDisplayed()
    }
}
