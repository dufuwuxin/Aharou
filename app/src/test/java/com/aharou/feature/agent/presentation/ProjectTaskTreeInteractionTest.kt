package com.aharou.feature.agent.presentation

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskMemberRun
import com.aharou.feature.agent.presentation.component.ProjectTaskWorkbench
import com.aharou.feature.agent.presentation.component.ProjectTaskProcess
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication
import org.junit.Assert.*
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
class ProjectTaskTreeInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun defaultTreeExpandsTaskRecordsAndTogglesChildVisibility() {
        val task = ProjectTask(id = "parent", workspace = "w", projectId = "p", title = "Parent", requirement = "Build")
        val child = task.copy(id = "child", parentId = "parent", title = "Child")
        val vm = mockk<ProjectTaskViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(ProjectTaskUiState(loading = false, tasks = listOf(task, child)))
        compose.setContent { MaterialTheme { ProjectTaskWorkbench("p", vm, {}, { _, _ -> }) } }
        compose.onNodeWithTag("project_tree_body").assertExists()
        compose.onNodeWithTag("project_map_body").assertDoesNotExist()
        compose.onNodeWithTag("task_node_parent").performClick()
        compose.onNodeWithTag("task_process_parent").assertExists()
        compose.onNodeWithTag("project_tree_body").performScrollToNode(hasTestTag("task_node_parent"))
        compose.onNodeWithTag("task_node_parent").performClick()
        compose.onNodeWithTag("task_node_child").assertDoesNotExist()
        compose.onNodeWithTag("task_node_parent").performClick()
        compose.onNodeWithTag("project_tree_body").performScrollToNode(hasTestTag("task_node_child"))
        compose.onNodeWithTag("task_node_child").assertExists()
    }
    @Test fun selectingRecordedParticipantShowsItsOwnConversationAndKeepsTrackingReachable() {
        val task = ProjectTask(id = "t", workspace = "w", title = "Task", requirement = "Build",
            processSessionIds = listOf("one", "two"), processMembers = mapOf(
                "one" to ProjectTaskMemberRun("writer", 1, 1), "two" to ProjectTaskMemberRun("reviewer", 2, 1)))
        compose.setContent { MaterialTheme {
            ProjectTaskProcess(task, listOf(task), {}, {}, { _, session -> Text(session, Modifier.testTag("record")) },
                modifier = Modifier.fillMaxSize())
        } }
        compose.onNodeWithTag("record").assertTextEquals("one")
        compose.onNode(hasText("reviewer", substring = true) and isSelectable()).performClick()
        compose.onNodeWithTag("record").assertTextEquals("two")
        compose.onNodeWithTag("task_member_tracking_t").assertIsDisplayed()
    }
}
