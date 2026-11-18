package com.aharou.core.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import compose.icons.FeatherIcons
import compose.icons.feathericons.Home
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
class FloatingTabBarGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val selected = mutableIntStateOf(0)
    private fun open(restricted: Boolean) {
        compose.setContent { MaterialTheme {
            val items = listOf("Project", "Config", "Settings").map { FloatingTabItem(FeatherIcons.Home, it) }
            if (restricted) FloatingTabBar(selected.intValue, { selected.intValue = it }, items, Color.White,
                modifier = Modifier.testTag("tabs"), draggableIndices = setOf(1, 2))
            else FloatingTabBar(selected.intValue, { selected.intValue = it }, items, Color.White,
                modifier = Modifier.testTag("tabs"))
        } }
    }
    private fun drag(from: String, to: String) {
        val root = compose.onNodeWithTag("tabs").fetchSemanticsNode().boundsInRoot
        val a = compose.onNodeWithText(from).fetchSemanticsNode().boundsInRoot.center - root.topLeft
        val b = compose.onNodeWithText(to).fetchSemanticsNode().boundsInRoot.center - root.topLeft
        compose.onNodeWithTag("tabs").performTouchInput {
            down(a); moveTo(a + Offset(16f, 0f), delayMillis = 16); moveTo(b, delayMillis = 200); up()
        }
    }
    @Test fun allThreePrimaryTabsAllowDragInBothDirections() {
        open(false)
        drag("Project", "Config")
        assertEquals(1, selected.intValue)
        compose.onNodeWithText("Config").performClick()
        drag("Config", "Settings")
        assertEquals(2, selected.intValue)
        drag("Settings", "Config")
        assertEquals(1, selected.intValue)
        drag("Config", "Project")
        assertEquals(0, selected.intValue)
        compose.onNodeWithText("Project").performClick()
        assertEquals(0, selected.intValue)
    }
    @Test fun unrestrictedCallersStillAllowProjectDrag() {
        open(false)
        drag("Project", "Config")
        assertEquals(1, selected.intValue)
    }
}
