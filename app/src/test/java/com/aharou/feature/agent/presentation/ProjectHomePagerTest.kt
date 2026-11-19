package com.aharou.feature.agent.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
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

    @Test fun swipeAndChipsSharePageAndKeepSessionState() {
        val vm = mockk<ProjectTaskViewModel>()
        every { vm.state } returns MutableStateFlow(ProjectTaskUiState(workspace = "/A", loading = false))
        compose.setContent { MaterialTheme {
            ProjectListScreen(vm, false, {}, {}, {}, {}, {
                var count by rememberSaveable { mutableIntStateOf(0) }
                Text("count=$count", Modifier.testTag("session_counter").clickable { count++ })
            })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.project_sessions)).performClick()
        compose.onNodeWithTag("session_counter").performClick().assertTextEquals("count=1")
        compose.onNodeWithTag("project_home_pager").performTouchInput { swipeRight() }
        compose.onNodeWithText(compose.activity.getString(R.string.project_home)).assertIsSelected()
        compose.onNodeWithTag("project_home_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText(compose.activity.getString(R.string.project_sessions)).assertIsSelected()
        compose.onNodeWithTag("session_counter").assertTextEquals("count=1")
    }
    @Test fun searchFiltersProjectsAndSurvivesSessionNavigationWithoutOpeningAnything() {
        val vm = mockk<ProjectTaskViewModel>()
        val first = com.aharou.feature.agent.domain.task.ProjectPlan(
            id = "alpha", workspace = "/projects/alpha", title = "Alpha", purpose = "API migration")
        val second = first.copy(id = "beta", title = "Beta", workspace = "/projects/beta", purpose = "UI cleanup")
        val state = MutableStateFlow(ProjectTaskUiState(workspace = "/A", loading = false, projects = listOf(first, second)))
        every { vm.state } returns state
        every { vm.canContinueInOwnedWorkspace(any()) } returns false
        var opened = 0
        compose.setContent { MaterialTheme {
            ProjectListScreen(vm, false, {}, { opened++ }, {}, {}, { Text("Sessions") })
        } }
        compose.onNodeWithTag("project_search").performTextInput("migration")
        compose.onNodeWithTag("project_alpha").assertExists()
        compose.onNodeWithTag("project_beta").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.project_sessions)).performClick()
        compose.onNodeWithText("Sessions").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.project_home)).performClick()
        compose.onNodeWithTag("project_search").assertTextContains("migration")
        compose.onNodeWithTag("project_search").performTextReplacement("absent")
        compose.onNodeWithTag("project_search_empty").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.common_clear)).performClick()
        compose.onNodeWithTag("project_alpha").assertExists()
        compose.onNodeWithTag("project_beta").assertExists()
        org.junit.Assert.assertEquals(0, opened)
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
