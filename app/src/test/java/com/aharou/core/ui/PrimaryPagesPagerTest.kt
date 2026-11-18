package com.aharou.core.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import org.junit.Assert.*
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
class PrimaryPagesPagerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var currentRoute = "projects"
    private var externalRoute: (String) -> Unit = {}

    @Composable private fun Fixture(nested: Boolean = false) {
        var route by rememberSaveable { mutableStateOf("projects") }
        // Read in this composition scope: reading only inside SideEffect does not
        // subscribe Fixture, so a child-only recomposition leaves currentRoute stale.
        val observedRoute = route
        SideEffect { currentRoute = observedRoute; externalRoute = { route = it } }
        MaterialTheme {
            PrimaryPagesPager(route, { route = it }, projects = {
                Column(Modifier.fillMaxSize().testTag("root_projects")) {
                    Text("Project header", Modifier.fillMaxWidth().height(100.dp).testTag("project_header"))
                    if (nested) {
                        val inner = rememberPagerState { 2 }
                        HorizontalPager(inner, modifier = Modifier.weight(1f).testTag("nested_projects")) {
                            Text("inner=$it", Modifier.fillMaxSize().testTag("inner_$it"))
                        }
                    }
                }
            }, settings = { config, _, onRootChange ->
                val name = if (config) "config" else "settings"
                var root by rememberSaveable { mutableStateOf(true) }
                var count by rememberSaveable { mutableIntStateOf(0) }
                SideEffect { onRootChange(root) }
                Column(Modifier.fillMaxSize().testTag("root_$name")) {
                    Text("$name=$count", Modifier.testTag("count_$name").clickable { count++ })
                    Text("detail_$name", Modifier.testTag("detail_$name").clickable { root = !root })
                    Text("root=$root", Modifier.testTag("state_$name"))
                }
            })
        }
    }

    private fun pager() = compose.onNodeWithTag("primary_pages_pager")
    private fun tab(id: Int) = compose.onNodeWithText(compose.activity.getString(id))
    private fun expectRoute(route: String) = compose.runOnIdle { assertEquals(route, currentRoute) }

    @Test fun pageMovesAndNeighborIsVisibleBeforeFingerUp() {
        compose.setContent { Fixture() }
        val initial = compose.onNodeWithTag("root_projects").getUnclippedBoundsInRoot().left.value
        pager().performTouchInput { down(center); moveBy(Offset(-width * .32f, 0f), 200) }
        // Clipped semantics bounds stay at zero while the page moves offscreen.
        val moved = compose.onNodeWithTag("root_projects").getUnclippedBoundsInRoot().left.value
        assertTrue("Page must move while pointer remains down", moved < initial - 1f)
        compose.onNodeWithTag("root_config").assertIsDisplayed()
        expectRoute("projects")
        pager().performTouchInput { up() }
    }

    @Test fun allThreePagesSwipeInBothDirections() {
        compose.setContent { Fixture() }
        pager().performTouchInput { swipeLeft() }; expectRoute("config")
        pager().performTouchInput { swipeLeft() }; expectRoute("settings")
        pager().performTouchInput { swipeRight() }; expectRoute("config")
        pager().performTouchInput { swipeRight() }; expectRoute("projects")
    }

    @Test fun bottomClicksAndRestorationRetainIndependentRootState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Fixture() }
        tab(R.string.bottom_tab_config).performClick()
        expectRoute("config")
        compose.onNodeWithTag("count_config").performClick().assertTextEquals("config=1")
        tab(R.string.bottom_tab_settings).performClick()
        expectRoute("settings")
        compose.onNodeWithTag("count_settings").performClick().assertTextEquals("settings=1")
        restoration.emulateSavedInstanceStateRestore()
        expectRoute("settings")
        compose.onNodeWithTag("count_settings").assertTextEquals("settings=1")
        tab(R.string.bottom_tab_config).performClick()
        compose.onNodeWithTag("count_config").assertTextEquals("config=1")
        tab(R.string.project_home).performClick(); expectRoute("projects")
    }

    @Test fun bottomDragMovesPageBeforeUpAndCanReachProject() {
        compose.setContent { Fixture() }
        val tabs = compose.onNodeWithTag("primary_pages_tabs")
        tabs.performTouchInput {
            down(Offset(width * .23f, height * .55f))
            moveTo(Offset(width * .49f, height * .55f), 200)
        }
        val configLeft = compose.onNodeWithTag("root_config").getUnclippedBoundsInRoot().left.value
        val right = pager().getUnclippedBoundsInRoot().right.value
        assertTrue("Bottom drag must move the real page before up", configLeft < right - 1f)
        expectRoute("projects")
        tabs.performTouchInput { moveTo(Offset(width * .75f, height * .55f), 200); up() }
        expectRoute("settings")
        tabs.performTouchInput {
            down(Offset(width * .75f, height * .55f))
            moveTo(Offset(width * .23f, height * .55f), 400); up()
        }
        expectRoute("projects")
    }

    @Test fun subpagesDisableParentSwipeAndBottomDragButKeepClickNavigation() {
        compose.setContent { Fixture() }
        tab(R.string.bottom_tab_config).performClick()
        compose.onNodeWithTag("detail_config").performClick()
        pager().performTouchInput { swipeLeft() }; expectRoute("config")
        compose.onNodeWithTag("primary_pages_tabs").performTouchInput { swipeRight() }
        expectRoute("config")
        tab(R.string.bottom_tab_settings).performClick(); expectRoute("settings")
        tab(R.string.bottom_tab_config).performClick()
        compose.onNodeWithTag("state_config").assertTextEquals("root=false")
        compose.onNodeWithTag("detail_config").performClick()
        pager().performTouchInput { swipeLeft() }; expectRoute("settings")
    }

    @Test fun nestedProjectPagerWinsInsideContentAndHeaderStillChangesPrimaryPage() {
        compose.setContent { Fixture(nested = true) }
        compose.onNodeWithTag("nested_projects").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("inner_1").assertIsDisplayed(); expectRoute("projects")
        compose.onNodeWithTag("project_header").performTouchInput { swipeLeft() }
        expectRoute("config")
    }

    @Test fun externalNavigationCoversPagerAndReturningRestoresSelectedRoot() {
        compose.setContent { Fixture() }
        tab(R.string.bottom_tab_settings).performClick()
        compose.runOnIdle { externalRoute("editor/{path}") }
        compose.onNodeWithTag("primary_pages_tabs").assertDoesNotExist()
        compose.runOnIdle { externalRoute("config") }
        expectRoute("config")
        compose.onNodeWithTag("root_config").assertIsDisplayed()
    }
}
