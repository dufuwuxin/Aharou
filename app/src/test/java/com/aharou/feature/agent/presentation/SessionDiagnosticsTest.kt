package com.aharou.feature.agent.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aharou.feature.agent.domain.prompt.InjectedContextStore
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.agent.domain.workflow.RequestContextComposition
import com.aharou.feature.agent.presentation.component.SessionDiagnosticsSheet
import com.aharou.feature.agent.presentation.component.sessionContextMetrics
import com.aharou.feature.agent.presentation.component.diagnosticSessionId
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
class SessionDiagnosticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun projectPlanningIsInsideDiagnosticsWithoutASecondReaderWindow() {
        compose.setContent {
            MaterialTheme {
                SessionDiagnosticsSheet("main", null, ChatMessagesState("main", emptyList(), true),
                    emptyList(), sessionContextMetrics(null, 0, 0), null, false, { false }, { false }, {},
                    projectConversation = { androidx.compose.material3.Text("planning history fixture") })
            }
        }
        compose.onNodeWithTag("diagnostics_tab_PLANNING").performClick()
        compose.onNodeWithText("planning history fixture").assertExists()
        compose.onNodeWithTag("diagnostics_tab_CONTEXT").performClick()
        compose.onNodeWithText("planning history fixture").assertDoesNotExist()
    }

    @Test fun ordinaryChatHasNoProjectPlanningTab() {
        compose.setContent {
            MaterialTheme {
                SessionDiagnosticsSheet("chat", null, ChatMessagesState("chat", emptyList(), true),
                    emptyList(), sessionContextMetrics(null, 0, 0), null, true, { false }, { false }, {})
            }
        }
        compose.onNodeWithTag("diagnostics_tab_PLANNING").assertDoesNotExist()
    }

    @Test fun projectBindingMustMatchBeforeDiagnosticsReadAnySession() {
        assertNull(diagnosticSessionId("old", true, null))
        assertNull(diagnosticSessionId("old", true, "new"))
        assertEquals("new", diagnosticSessionId("new", true, "new"))
        assertEquals("ordinary", diagnosticSessionId("ordinary", false, null))
    }

    @Test fun requestUsageKeepsItsOwnBudgetWhenSelectedModelChanges() {
        val usage = sessionContextMetrics(AgentEvent.ContextUsage(80, 100, true), 10000, 1000)
        assertEquals(80, usage.used)
        assertEquals(100, usage.budget)
        assertEquals(20, usage.remaining)
        assertEquals(0.8f, usage.progress!!, 0.001f)
        assertEquals(1000, usage.currentModelBudget)
        assertTrue(usage.estimated)
    }

    @Test fun missingRequestBudgetNeverMixesLastUsageWithCurrentModel() {
        val usage = sessionContextMetrics(null, 80, 1000)
        assertEquals(80, usage.used)
        assertNull(usage.budget)
        assertNull(usage.progress)
        assertNull(usage.remaining)
        assertNull(sessionContextMetrics(null, 0, 0).used)
    }

    @Test fun zeroBudgetAndOverrunDoNotCreateInvalidProgress() {
        assertNull(sessionContextMetrics(AgentEvent.ContextUsage(80, 0, true), 0, 100).progress)
        val over = sessionContextMetrics(AgentEvent.ContextUsage(120, 100, false), 0, 100)
        assertEquals(0, over.remaining)
        assertEquals(1f, over.progress!!, 0f)
    }

    @Test fun actualRequestSnapshotIsVisibleAndSwitchingSessionDropsTheOldTextAndTab() {
        val store = InjectedContextStore()
        val version = store.beginRequest("first")
        store.recordRequest("first", RequestContextComposition.capture(version, "fixture actual injection", emptyList(), emptyList()))
        val selected = mutableStateOf("first")
        compose.setContent {
            MaterialTheme {
                SessionDiagnosticsSheet(selected.value, null, ChatMessagesState(selected.value, emptyList(), true),
                    emptyList(), sessionContextMetrics(null, 0, 0), store.bySession.value[selected.value],
                    false, { false }, { false }, {})
            }
        }
        compose.onNodeWithText("fixture actual injection", substring = true).assertExists()
        compose.onNodeWithTag("diagnostics_tab_ACTIVITY").performClick()
        compose.runOnIdle { selected.value = "second" }
        compose.onNodeWithText("fixture actual injection", substring = true).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(com.aharou.R.string.session_context_no_snapshot)).assertExists()
    }

    @Test fun projectMapRemainsReadOnlyWithinUnifiedDiagnostics() {
        compose.setContent {
            MaterialTheme {
                SessionDiagnosticsSheet("first", null, ChatMessagesState("first", emptyList(), true),
                    emptyList(), sessionContextMetrics(null, 0, 0), null, false,
                    { error("Project map must not locate messages") }, { error("Project map must not jump") }, {})
            }
        }
        compose.onNodeWithTag("diagnostics_tab_CONVERSATION").performClick()
        compose.onNodeWithText(compose.activity.getString(com.aharou.R.string.conversation_map_project_readonly)).assertExists()
    }
}
