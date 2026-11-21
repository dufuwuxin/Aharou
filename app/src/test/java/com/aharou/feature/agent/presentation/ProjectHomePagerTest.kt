package com.aharou.feature.agent.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.presentation.component.ProjectListScreen
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectHomePagerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun browsingProjectsKeepsTheVisibleSessionSectionState() {
        val vm = mockk<ProjectTaskViewModel>()
        val projects = List(8) { index ->
            ProjectPlan(id = "p$index", workspace = "/projects/p$index", title = "Project $index", workspaceOwnership = "owned")
        }
        every { vm.state } returns MutableStateFlow(ProjectTaskUiState(workspace = "/A", loading = false, projects = projects))
        compose.setContent { MaterialTheme {
            ProjectListScreen(vm, false, {}, {}, {}, {}, {
                var count by rememberSaveable { mutableIntStateOf(0) }
                Text("count=$count", Modifier.testTag("session_counter").clickable { count++ })
            })
        } }
        val sessions = compose.onNodeWithTag("session_counter")
        sessions.assertIsDisplayed().performClick().assertTextEquals("count=1")
        val projectList = compose.onNodeWithTag("project_home_list")
        projectList.performScrollToNode(hasTestTag("project_p7"))
        compose.onNodeWithTag("project_p7").assertIsDisplayed()
        sessions.assertIsDisplayed().assertTextEquals("count=1")
        projectList.performScrollToNode(hasTestTag("project_p0"))
        compose.onNodeWithTag("project_p0").assertIsDisplayed()
        sessions.performClick().assertTextEquals("count=2")
    }

    @Test fun searchFiltersProjectsAndSurvivesSessionInteractionWithoutOpeningAProject() {
        val vm = mockk<ProjectTaskViewModel>()
        val first = ProjectPlan(id = "alpha", workspace = "/projects/alpha", title = "Alpha",
            purpose = "API migration", workspaceOwnership = "owned")
        val second = first.copy(id = "beta", title = "Beta", workspace = "/projects/beta", purpose = "UI cleanup")
        val state = MutableStateFlow(ProjectTaskUiState(workspace = "/A", loading = false, projects = listOf(first, second)))
        every { vm.state } returns state
        var opened = 0
        var sessionActions = 0
        compose.setContent { MaterialTheme {
            ProjectListScreen(vm, false, {}, { opened++ }, {}, {}, {
                Text("Existing session action", Modifier.clickable { sessionActions++ })
            })
        } }
        compose.onNodeWithTag("project_search").assertDoesNotExist()
        compose.onNodeWithTag("project_search_open").performClick()
        compose.onNodeWithTag("project_search").performTextInput("migration")
        compose.onNodeWithTag("project_alpha").assertExists()
        compose.onNodeWithTag("project_beta").assertDoesNotExist()
        compose.onNodeWithText("Existing session action").assertIsDisplayed().performClick()
        compose.onNodeWithTag("project_search").assertTextContains("migration")
        compose.onNodeWithTag("project_search").performTextReplacement("absent")
        compose.onNodeWithTag("project_search_empty").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.common_clear)).performClick()
        compose.onNodeWithTag("project_search").assertDoesNotExist()
        compose.onNodeWithTag("project_alpha").assertExists()
        compose.onNodeWithTag("project_home_list").performScrollToNode(hasTestTag("project_beta"))
        compose.onNodeWithTag("project_beta").assertIsDisplayed()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(0, opened)
            org.junit.Assert.assertEquals(1, sessionActions)
        }
    }

    @Test fun projectMenuFloatsWithoutHeaderBackAndRetainsInjectedActions() {
        val vm = mockk<ProjectTaskViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(ProjectTaskUiState(loading = false,
            projects = listOf(com.aharou.feature.agent.domain.task.ProjectPlan(
                id = "p", workspace = "/p", title = "Hidden project title"))))
        val menu: @Composable (() -> Unit) -> Unit = { dismiss ->
            androidx.compose.material3.DropdownMenuItem(text = { Text("Diagnostics action") }, onClick = dismiss)
        }
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(
                com.aharou.feature.agent.presentation.component.LocalProjectChatMenu provides menu,
                com.aharou.feature.agent.presentation.component.LocalProjectDiagnostics provides {}
            ) {
                com.aharou.feature.agent.presentation.component.ProjectTaskWorkbench("p", vm, {}, { _, _ -> })
            }
        } }
        compose.onNodeWithTag("project_compact_header").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.project_back)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.project_view_actions)).performClick()
        compose.onNodeWithText("Diagnostics action").assertIsDisplayed().performClick()
    }

}
