package com.aharou.feature.agent.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.feature.agent.presentation.component.ProjectRootConversation
import com.aharou.feature.agent.presentation.component.ProjectTaskConversation
import com.aharou.feature.agent.presentation.component.ProjectTaskProcess
import com.aharou.feature.agent.domain.task.ProjectTask
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectTaskConversationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val agent = mockk<AIAgentViewModel>(relaxed = true)
    private val active = MutableStateFlow(emptySet<String>())
    private val streams = MutableStateFlow<String?>("stale stream must not mean running")
    private val history = MutableStateFlow(listOf(
        AgentUIMessage(id = "user", role = MessageRole.USER, content = "original request"),
        AgentUIMessage(id = "thinking", role = MessageRole.ASSISTANT, content = "working", reasoning = "real returned reasoning"),
        AgentUIMessage(id = "tool", role = MessageRole.TOOL, content = "real tool output", toolName = "Bash", toolArgs = "{}"),
        AgentUIMessage(id = "final", role = MessageRole.ASSISTANT, content = "complete reply")))

    private fun prepare() {
        every { agent.activeAgentSessions } returns active
        every { agent.processMessages("old", any()) } returns history
        every { agent.processMessages("new", any()) } returns MutableStateFlow(listOf(
            AgentUIMessage(id = "new-user", role = MessageRole.USER, content = "new request"),
            AgentUIMessage(id = "notice", role = MessageRole.ASSISTANT, content = "interruption", isBackgroundNotification = true)))
        every { agent.processText(any()) } returns streams
        every { agent.processReasoning(any()) } returns MutableStateFlow(null)
        every { agent.processTools(any()) } returns MutableStateFlow(emptyList())
    }

    private fun reveal(text: String) {
        compose.onNodeWithTag("task_process_messages").performScrollToNode(hasText(text, substring = true))
        compose.onAllNodes(hasText(text, substring = true)).onFirst().assertIsDisplayed()
    }

    @Test fun defaultLatestRoundShowsActualReplyReasoningAndToolWithoutOpeningSections() {
        prepare()
        compose.setContent { MaterialTheme { ProjectTaskConversation("old", agent) {} } }
        reveal("complete reply")
        reveal("real returned reasoning")
        reveal("real tool output")
        compose.onNodeWithText(compose.activity.getString(R.string.project_process_live)).assertDoesNotExist()
        compose.onNodeWithText("stale stream must not mean running").assertDoesNotExist()
    }

    @Test fun activeWithoutTokensIsLiveAndStaleTokensAfterStopAreNotLive() {
        prepare()
        active.value = setOf("old")
        streams.value = null
        compose.setContent { MaterialTheme { ProjectTaskConversation("old", agent) {} } }
        compose.onNodeWithTag("task_process_messages").performScrollToNode(
            hasText(compose.activity.getString(R.string.project_process_live)))
        compose.onNodeWithText(compose.activity.getString(R.string.project_process_live)).assertIsDisplayed()
        compose.runOnIdle { streams.value = "stale stream must not mean running"; active.value = emptySet() }
        compose.onNodeWithText(compose.activity.getString(R.string.project_process_live)).assertDoesNotExist()
        reveal("complete reply")
        compose.onNodeWithText("stale stream must not mean running").assertDoesNotExist()
    }

    @Test fun returnedReasoningCollapseSurvivesReadingAnotherRoot() {
        prepare()
        compose.setContent { MaterialTheme { ProjectRootConversation(listOf("old", "new"), agent) {} } }
        reveal("real returned reasoning")
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.common_collapse)).onFirst().performClick()
        compose.onNodeWithTag("project_root_record_new").performClick()
        reveal("new request")
        compose.onNodeWithTag("project_root_record_old").performClick()
        reveal("working")
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.common_expand)).onFirst().assertIsDisplayed()
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.common_expand)).onFirst().performClick()
        reveal("real returned reasoning")
    }

    @Test fun rootRoundCollapseSurvivesReadingAnotherRecord() {
        prepare()
        compose.setContent { MaterialTheme { ProjectRootConversation(listOf("old", "new"), agent) {} } }
        reveal("real returned reasoning")
        val round = compose.activity.getString(R.string.project_process_round, 1)
        compose.onNodeWithTag("task_process_messages").performScrollToNode(hasText(round))
        compose.onNodeWithText(round).performClick()
        compose.onNodeWithTag("project_root_record_new").performClick()
        reveal("new request")
        compose.onNodeWithTag("project_root_record_old").performClick()
        compose.onNodeWithTag("task_process_messages").performScrollToNode(hasText(round))
        compose.onNodeWithText("working").assertDoesNotExist()
        compose.onNodeWithText(round).performClick()
        reveal("real returned reasoning")
    }

    @Test fun taskRoundCollapseSurvivesReadingAnotherMemberRecord() {
        prepare()
        val task = ProjectTask(id = "task", workspace = "w", title = "Task", requirement = "requirement",
            sessionIds = listOf("old", "new"))
        compose.setContent { MaterialTheme {
            ProjectTaskProcess(task, listOf(task), onEdit = {}, onAccept = {}, readOnly = true,
                conversation = { _, id -> ProjectTaskConversation(id, agent) {} })
        } }
        reveal("real returned reasoning")
        val round = compose.activity.getString(R.string.project_process_round, 1)
        compose.onNodeWithTag("task_process_messages").performScrollToNode(hasText(round))
        compose.onNodeWithText(round).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.project_process_record, 2)).performClick()
        reveal("new request")
        compose.onNodeWithText(compose.activity.getString(R.string.project_process_record, 1)).performClick()
        compose.onNodeWithTag("task_process_messages").performScrollToNode(hasText(round))
        compose.onNodeWithText("working").assertDoesNotExist()
        compose.onNodeWithText(round).performClick()
        reveal("real returned reasoning")
    }

    @Test fun rootPrefersRealHistoryThenActiveRootAndHonorsManualHistorySelection() {
        prepare()
        val records = androidx.compose.runtime.mutableStateOf(listOf("new", "old"))
        compose.setContent { MaterialTheme { ProjectRootConversation(records.value, agent) {} } }
        reveal("complete reply")
        compose.runOnIdle { active.value = setOf("new") }
        reveal("new request")
        compose.onNodeWithTag("project_root_record_old").performClick()
        reveal("complete reply")
        compose.runOnIdle { active.value = emptySet(); records.value = listOf("old", "new") }
        reveal("complete reply")
        compose.runOnIdle { active.value = setOf("new") }
        reveal("complete reply")
    }
}
