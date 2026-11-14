package com.aharou.feature.agent.presentation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.presentation.component.ProjectTaskMap
import org.junit.Assert.assertEquals
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
class ProjectTaskMapInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun scaledRootRemainsClickableAndReturnsAfterPanningAcrossViewport() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                ProjectTaskMap("p", "Project root", emptyList(), null, {}, {}, {}, {},
                    modifier = Modifier.fillMaxSize(), onOpenRoot = { opens++ })
            }
        }
        compose.onNodeWithTag("map_viewport").performTouchInput {
            pinch(Offset(center.x - 80f, center.y), Offset(center.x - 64f, center.y),
                Offset(center.x + 80f, center.y), Offset(center.x + 64f, center.y), durationMillis = 200)
        }
        compose.onNodeWithText("Project root").performTouchInput { click(center) }
        assertEquals(1, opens)
        val before = compose.onNodeWithTag("map_node_project:p").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("map_viewport").performTouchInput { swipeLeft(durationMillis = 200) }
        compose.onNodeWithTag("map_node_project:p").assertDoesNotExist()
        compose.onNodeWithTag("map_viewport").performTouchInput { swipeRight(durationMillis = 200) }
        compose.onNodeWithTag("map_node_project:p").assertIsDisplayed()
        compose.onNodeWithText("Project root").performTouchInput { click(center) }
        assertEquals(2, opens)
        val after = compose.onNodeWithTag("map_node_project:p").fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, after.left, 2f)
        assertEquals(before.top, after.top, 2f)
    }

    @Test fun expandedOutlineSurvivesRepeatedPinchAndReturnsToSameGeometry() {
        val task = ProjectTask(id = "a", workspace = "w", projectId = "p", title = "Task", requirement = "Implement feature",
            constraints = listOf("Keep accounts", "Keep history"))
        compose.setContent {
            MaterialTheme {
                ProjectTaskMap("p", "Project", listOf(task), null, {}, {}, {}, {},
                    modifier = Modifier.fillMaxSize(), header = { menu -> menu {} })
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.project_task_expand_all)).performClick()
        val before = compose.onNodeWithTag("map_outline_a").fetchSemanticsNode().boundsInRoot
        repeat(3) {
            compose.onNodeWithTag("map_viewport").performTouchInput {
                pinch(Offset(center.x - 64f, center.y), Offset(center.x - 80f, center.y),
                    Offset(center.x + 64f, center.y), Offset(center.x + 80f, center.y), durationMillis = 200)
            }
            compose.onNodeWithTag("map_viewport").performTouchInput {
                pinch(Offset(center.x - 80f, center.y), Offset(center.x - 64f, center.y),
                    Offset(center.x + 80f, center.y), Offset(center.x + 64f, center.y), durationMillis = 200)
            }
        }
        val after = compose.onNodeWithTag("map_outline_a").fetchSemanticsNode().boundsInRoot
        assertEquals(before.width, after.width, 1f)
        assertEquals(before.height, after.height, 1f)
        compose.onNodeWithTag("map_outline_a").assertExists()
    }
}
