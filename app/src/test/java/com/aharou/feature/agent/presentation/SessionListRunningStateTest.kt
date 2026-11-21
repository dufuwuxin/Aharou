package com.aharou.feature.agent.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.feature.agent.domain.model.ChatSession
import com.aharou.feature.workspace.domain.model.Workspace
import com.aharou.feature.agent.presentation.component.SessionListScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class SessionListRunningStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun groupedSessionsShowWorkspaceHeadersAndMoveAction() {
        val alpha = ChatSession("alpha", "Alpha session", 1, 1, workspacePath = "/projects/alpha")
        val beta = ChatSession("beta", "Beta session", 2, 2, workspacePath = "/projects/beta")
        val movedTo = mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                SessionListScreen(
                    sessions = emptyList(), currentSessionId = null, agentStates = emptyMap(),
                    awaitingPermissionSessionIds = emptySet(), workspaceName = "",
                    onSelect = {}, onDelete = {}, onNewSession = {}, showNewSession = false,
                    sessionGroups = mapOf("/projects/alpha" to listOf(alpha), "/projects/beta" to listOf(beta)),
                    availableWorkspaces = listOf(Workspace("beta", "/projects/beta")),
                    onMove = { _, path -> movedTo.value = path }
                )
            }
        }
        compose.onNodeWithText("/projects/alpha").assertExists()
        compose.onNodeWithText("/projects/beta").assertExists()
        compose.onNodeWithText(alpha.title).assertExists()
        compose.onAllNodesWithContentDescription(compose.activity.getString(com.aharou.R.string.session_workspace_move)).onFirst().performClick()
        compose.onNodeWithText(compose.activity.getString(com.aharou.R.string.session_workspace_move_title)).assertExists()
        compose.onAllNodesWithText("beta").onLast().performClick()
        compose.runOnIdle { assertEquals("/projects/beta", movedTo.value) }
    }

    @Test fun staleStreamingDoesNotShowARunningDotAndJobCompletionRemovesIt() {
        val active = mutableStateOf(emptySet<String>())
        val session = ChatSession("s", "State regression session", 1, 1, workspacePath = "w")
        compose.setContent {
            MaterialTheme {
                SessionListScreen(listOf(session), null, mapOf("s" to AgentUIState.Streaming), emptySet(), "w",
                    {}, {}, {}, showNewSession = false, activeSessionIds = active.value)
            }
        }
        val idleLeft = compose.onNodeWithText(session.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        compose.runOnIdle { active.value = setOf("s") }
        compose.waitForIdle()
        val runningLeft = compose.onNodeWithText(session.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertTrue("The real job adds its running indicator", runningLeft > idleLeft)
        compose.runOnIdle { active.value = emptySet() }
        compose.waitForIdle()
        val stoppedLeft = compose.onNodeWithText(session.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertEquals(idleLeft, stoppedLeft, 0.1f)
    }
}
