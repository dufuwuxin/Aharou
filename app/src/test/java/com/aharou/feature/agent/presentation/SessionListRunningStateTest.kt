package com.aharou.feature.agent.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.feature.agent.domain.model.ChatSession
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
