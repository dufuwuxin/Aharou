package com.aharou.feature.agent.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.aharou.R
import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.presentation.component.ProjectMindMapScreen
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Central runner executes these; authoring this file is not evidence of a device pass. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectMindMapScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val project = ProjectPlan(id = "p", workspace = "/A", title = "Real project")
    private val task = ProjectTask(id = "t", workspace = "/A", projectId = "p", title = "Real task",
        requirement = "Real requirement", owner = "Real owner", sessionIds = listOf("s"))

    @Test fun fallbackPreviewOffersDraftSaveWithoutStartingOrWritingImmediately() {
        val vm = mockk<ProjectTaskViewModel>(relaxed = true)
        val preview = com.aharou.feature.agent.data.task.ProjectRequirementPreview(project, listOf(task),
            "first\nsecond", listOf(task.copy(id = "new1"), task.copy(id = "new2", title = "Second")), true)
        every { vm.state } returns MutableStateFlow(ProjectTaskUiState(workspace = "/A", projects = listOf(project),
            tasks = listOf(task), loading = false, requirementPreview = preview))
        compose.setContent { MaterialTheme { ProjectMindMapScreen("p", vm) {} } }
        compose.onNodeWithTag("pmap_add").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.pmap_plan_preview)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.pmap_save_and_start)).assertDoesNotExist()
        verify(exactly = 0) { vm.confirmRequirementPreview(any(), any()) }
        compose.onNodeWithText(compose.activity.getString(R.string.pmap_save_draft)).performClick()
        verify(exactly = 1) { vm.confirmRequirementPreview(any(), false) }
        verify(exactly = 0) { vm.startProject(any()) }
        verify(exactly = 0) { vm.appendRequirements(any(), any(), any()) }
    }

    @Test fun roleToTaskThenBackPreservesInternalSourceAndRootBackExits() {
        val vm = mockk<ProjectTaskViewModel>(relaxed = true)
        val state = MutableStateFlow(ProjectTaskUiState(workspace = "/A", projects = listOf(project), tasks = listOf(task), loading = false))
        every { vm.state } returns state
        every { vm.projectReceipts("/A", listOf("s"), 300) } returns MutableStateFlow(emptyList())
        var exits = 0
        compose.setContent { MaterialTheme { ProjectMindMapScreen("p", vm) { exits++ } } }
        compose.onNodeWithTag("pmap_node_role:Real owner").performClick()
        compose.onNodeWithTag("pmap_detail_heading").assertTextEquals("Real owner")
        val taskLink = hasText("Real task", substring = true) and hasClickAction() and !hasTestTag("pmap_node_task:t")
        compose.onNodeWithTag("pmap_reading").performScrollToNode(taskLink)
        compose.onNode(taskLink).performClick()
        compose.onNodeWithTag("pmap_detail_heading").assertTextEquals("Real task")
        compose.onNodeWithTag("pmap_back").performClick()
        compose.onNodeWithTag("pmap_detail_heading").assertTextEquals("Real owner")
        compose.onNodeWithTag("pmap_back").performClick()
        compose.onNodeWithTag("pmap_back").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
        verify(exactly = 0) { vm.openProject(any(), any()) }
    }

    @Test fun deniedIsNotFailureAndSummariesHaveNoFabricatedDiffOrAttempt() {
        val vm = mockk<ProjectTaskViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(ProjectTaskUiState(workspace = "/A", projects = listOf(project), tasks = listOf(task), loading = false))
        every { vm.projectReceipts("/A", listOf("s"), 300) } returns MutableStateFlow(listOf(
            ToolCallReceiptEntity(id = 1, sessionId = "s", createdAt = 1, category = "DENIED", toolName = "writeFile"),
            ToolCallReceiptEntity(id = 2, sessionId = "s", createdAt = 2, status = "ERROR", toolName = "writeFile"),
            ToolCallReceiptEntity(id = 3, sessionId = "s", createdAt = 3, status = "SUCCESS", toolName = "writeFile")))
        compose.setContent { MaterialTheme { ProjectMindMapScreen("p", vm) {} } }
        compose.onNodeWithTag("pmap_node_task:t").performClick()
        compose.onNodeWithTag("pmap_tab_log").performClick()
        compose.onNodeWithTag("pmap_reading").performScrollToNode(hasTestTag("pmap_filter_DENIED"))
        compose.onNodeWithTag("pmap_filter_DENIED").performClick()
        compose.onNodeWithTag("pmap_reading").performScrollToNode(hasTestTag("pmap_receipt_1"))
        compose.onNodeWithTag("pmap_receipt_1").performClick()
        compose.onNodeWithTag("pmap_receipt_2").assertDoesNotExist()
        compose.onNodeWithTag("pmap_reading").performScrollToNode(hasText(compose.activity.getString(R.string.pmap_no_diff)))
        compose.onNodeWithText(compose.activity.getString(R.string.pmap_no_diff)).assertIsDisplayed()
    }

    @Test fun restoringUiKeepsTaskAndReceiptTabWithoutStartingExecution() {
        val vm = mockk<ProjectTaskViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(ProjectTaskUiState(workspace = "/A", projects = listOf(project), tasks = listOf(task), loading = false))
        every { vm.projectReceipts("/A", listOf("s"), 300) } returns MutableStateFlow(emptyList())
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ProjectMindMapScreen("p", vm) {} } }
        compose.onNodeWithTag("pmap_node_task:t").performClick()
        compose.onNodeWithTag("pmap_tab_log").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("pmap_tab_log").assertIsSelected()
        compose.onNodeWithTag("pmap_detail_heading").assertTextEquals("Real task")
    }

    @Test fun emptyProjectHasRealRootAndTwoBranchesWithoutInventedRoles() {
        val vm = mockk<ProjectTaskViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(ProjectTaskUiState(workspace = "/A", projects = listOf(project), loading = false))
        compose.setContent { MaterialTheme { ProjectMindMapScreen("p", vm) {} } }
        compose.onNodeWithTag("pmap_node_map").assertExists()
        compose.onNodeWithTag("pmap_node_team").assertExists()
        compose.onNodeWithTag("pmap_node_work").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.pmap_no_roles)).assertExists()
        verify(exactly = 0) { vm.projectReceipts(any(), any(), any()) }
    }
}
