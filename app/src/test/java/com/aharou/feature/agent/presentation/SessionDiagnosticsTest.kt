package com.aharou.feature.agent.presentation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Modifier
import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.presentation.observe.*
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskMemberRun
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

    @Test fun selectedMemberTasksUseItsStoredWorkspaceNotRootOrGlobalSelection() {
        val root = com.aharou.feature.agent.domain.subagent.TreeSession("root", null, "Root", workspacePath = "/A")
        val member = root.copy(id = "member", parentId = "root", title = "Member", workspacePath = "/B")
        val unknown = root.copy(id = "unknown", workspacePath = "")
        fun task(id: String, workspace: String, session: String) = ProjectTask(id = id, workspace = workspace,
            title = id, requirement = "Build", processSessionIds = listOf(session))
        val snapshots = mapOf("/A" to listOf(task("root-A", "/A", "root"), task("wrong-A", "/A", "member")),
            "/B" to listOf(task("member-B", "/B", "member"), task("wrong-C", "/C", "member")),
            "/C" to listOf(task("global-C", "/C", "member")))
        val requested = mutableListOf<String>()
        val links = observeTaskLinksBySession(listOf(root, member, unknown)) { workspace ->
            requested += workspace
            snapshots[workspace].orEmpty()
        }
        assertEquals(listOf("root-A"), links.getValue("root").map { it.id })
        assertEquals(listOf("member-B"), links.getValue("member").map { it.id })
        assertTrue(links.getValue("unknown").isEmpty())
        assertEquals(listOf("/A", "/B"), requested)
    }

    @Test fun fileChangesRequireSuccessfulPairedWritesAndExactMemberScope() {
        val rows = listOf(
            AgentMessageEntity("calls", "member", "ASSISTANT", "", 1, toolCallsJson = """[{"id":"ok","name":"editFile","arguments":{"path":"/work/a.kt"}},{"id":"bad","name":"writeFile","arguments":{"path":"/work/b.kt"}},{"id":"pending","name":"writeFile","arguments":{"path":"/work/pending.kt"}},{"id":"unknown","name":"writeFile","arguments":{"path":"/work/unknown.kt"}}]"""),
            AgentMessageEntity("success", "member", "TOOL", """{"status":"success"}""", 2, toolCallId = "ok", toolName = "editFile"),
            AgentMessageEntity("failed", "member", "TOOL", """{"status":"success"}""", 3, toolCallId = "bad", toolName = "writeFile", isError = true),
            AgentMessageEntity("unknown", "member", "TOOL", "no structured status", 4, toolCallId = "unknown", toolName = "writeFile"),
            AgentMessageEntity("orphan", "member", "TOOL", """{"status":"success"}""", 5, toolCallId = "orphan", toolName = "writeFile"),
            AgentMessageEntity("other", "elsewhere", "TOOL", """{"status":"success"}""", 6, toolCallId = "pending", toolName = "writeFile"))
        val changes = observeFileChanges("member", rows)
        assertEquals(listOf("/work/a.kt"), changes.map { it.path })
        assertEquals("success", changes.single().toolMessageId)
        assertTrue(observeFileChanges("elsewhere", rows).isEmpty())
        val task = ProjectTask(id = "t", workspace = "w", title = "Linked task", requirement = "Build",
            processSessionIds = listOf("member"), processMembers = mapOf("member" to ProjectTaskMemberRun("writer", 1, 1, "DONE")))
        assertEquals("writer", observeTaskLinks("member", listOf(task)).single().role)
        assertTrue(observeTaskLinks("elsewhere", listOf(task)).isEmpty())
    }

    @Test fun verticalMemberSelectionSwapsActualTraceWithoutLeakingPreviousMemberFiles() {
        val selected = mutableStateOf<String?>(null)
        val members = listOf(ObserveMemberItem("main", "Main", null, 0, ObserveRunState.UNKNOWN, true),
            ObserveMemberItem("worker", "Worker", "writer", 1, ObserveRunState.DONE))
        compose.setContent { MaterialTheme {
            val worker = selected.value == "worker"
            ObserveScreen(ObservePanelUiState(sessionId = "main", members = members, observingSelf = !worker,
                observedTitle = if (worker) "Worker" else "Main", observedMemberId = selected.value,
                fileChanges = if (worker) listOf(ObserveFileChange("/work/actual.kt", "editFile", "receipt", 1L)) else emptyList(),
                taskLinks = if (worker) listOf(ObserveTaskLink("task", "Actual task", "writer", "DONE")) else emptyList()),
                {}, {}, { selected.value = it }, {})
        } }
        compose.onNodeWithTag("observe_member_worker").performClick().assertIsSelected()
        compose.onNodeWithText("/work/actual.kt").assertExists()
        compose.onNodeWithTag("observe_task_task").assertExists()
        compose.onNodeWithTag("observe_member_main").performClick().assertIsSelected()
        compose.onNodeWithText("/work/actual.kt").assertDoesNotExist()
        compose.onNodeWithTag("observe_task_task").assertDoesNotExist()
    }

    @Test fun projectSectionsExpandIndependentlyAndPlanningReaderHasFiniteHeight() {
        compose.setContent {
            MaterialTheme {
                SessionDiagnosticsSheet("main", null, ChatMessagesState("main", emptyList(), true),
                    emptyList(), sessionContextMetrics(null, 0, 0), null, false, { false }, { false }, {},
                    projectConversation = {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(50) { Text("planning history fixture $it") }
                        }
                    })
            }
        }
        compose.onNodeWithTag("diagnostics_content_CONTEXT").assertExists()
        compose.onNodeWithTag("diagnostics_content_PLANNING").assertDoesNotExist()
        compose.onNodeWithTag("diagnostics_content_CONVERSATION").assertDoesNotExist()
        compose.onNodeWithTag("diagnostics_content_ACTIVITY").assertDoesNotExist()
        compose.onNodeWithTag("diagnostics_content_MEMBERS").assertDoesNotExist()
        compose.onNodeWithTag("diagnostics_section_PLANNING").performScrollTo().performClick()
        compose.onNodeWithText("planning history fixture 0").assertExists()
        compose.onNodeWithTag("diagnostics_content_CONTEXT").assertExists()
        compose.onNodeWithTag("diagnostics_section_PLANNING").performScrollTo().performClick()
        compose.onNodeWithText("planning history fixture 0").assertDoesNotExist()
        compose.onNodeWithTag("diagnostics_section_CONTEXT").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics_content_CONTEXT").assertDoesNotExist()
        compose.onNodeWithTag("diagnostics_section_CONTEXT").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics_content_CONTEXT").assertExists()
    }

    @Test fun ordinaryChatHasNoProjectPlanningSection() {
        compose.setContent {
            MaterialTheme {
                SessionDiagnosticsSheet("chat", null, ChatMessagesState("chat", emptyList(), true),
                    emptyList(), sessionContextMetrics(null, 0, 0), null, true, { false }, { false }, {})
            }
        }
        compose.onNodeWithTag("diagnostics_section_PLANNING").assertDoesNotExist()
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

    @Test fun actualRequestSnapshotAndExpandedSectionsResetWhenSessionChanges() {
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
        compose.onNodeWithTag("diagnostics_section_CONTEXT").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics_section_ACTIVITY").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics_content_ACTIVITY").assertExists()
        compose.runOnIdle { selected.value = "second" }
        compose.onNodeWithTag("diagnostics_content_CONTEXT").assertExists()
        compose.onNodeWithTag("diagnostics_content_ACTIVITY").assertDoesNotExist()
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
        compose.onNodeWithTag("diagnostics_section_CONVERSATION").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(com.aharou.R.string.conversation_map_project_readonly)).assertExists()
        compose.onNodeWithTag("diagnostics_section_CONVERSATION").performScrollTo().performClick()
        compose.onNodeWithTag("conversation_map_sheet").assertDoesNotExist()
    }

    @Test fun activityOnlyShowsCurrentSessionReceiptsAndCloseRemainsReachable() {
        val selected = mutableStateOf("first")
        var dismissed = false
        val receipts = listOf(
            ToolCallReceiptEntity(id = 1, sessionId = "first", toolName = "first-tool", createdAt = 1),
            ToolCallReceiptEntity(id = 2, sessionId = "second", toolName = "second-tool", createdAt = 2),
        )
        compose.setContent {
            MaterialTheme {
                SessionDiagnosticsSheet(selected.value, null, ChatMessagesState(selected.value, emptyList(), true),
                    receipts, sessionContextMetrics(null, 0, 0), null, true, { false }, { false }, { dismissed = true })
            }
        }
        compose.onNodeWithTag("diagnostics_section_ACTIVITY").performScrollTo().performClick()
        compose.onNodeWithText("first-tool").assertExists()
        compose.onNodeWithText("second-tool").assertDoesNotExist()
        compose.runOnIdle { selected.value = "second" }
        compose.onNodeWithTag("diagnostics_content_ACTIVITY").assertDoesNotExist()
        compose.onNodeWithTag("diagnostics_section_ACTIVITY").performScrollTo().performClick()
        compose.onNodeWithText("second-tool").assertExists()
        compose.onNodeWithText("first-tool").assertDoesNotExist()
        compose.onNodeWithTag("diagnostics_close").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun losingProjectPlanningCapabilityResetsSectionsForTheSameSession() {
        val project = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                SessionDiagnosticsSheet("main", null, ChatMessagesState("main", emptyList(), true),
                    emptyList(), sessionContextMetrics(null, 0, 0), null, false, { false }, { false }, {},
                    projectConversation = if (project.value) ({ Text("planning fixture") }) else null)
            }
        }
        compose.onNodeWithTag("diagnostics_section_CONTEXT").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics_section_PLANNING").performScrollTo().performClick()
        compose.onNodeWithText("planning fixture").assertExists()
        compose.runOnIdle { project.value = false }
        compose.onNodeWithTag("diagnostics_section_PLANNING").assertDoesNotExist()
        compose.onNodeWithText("planning fixture").assertDoesNotExist()
        compose.onNodeWithTag("diagnostics_content_CONTEXT").assertExists()
    }
}
