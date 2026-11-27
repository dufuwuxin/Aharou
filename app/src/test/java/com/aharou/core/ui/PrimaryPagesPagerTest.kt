package com.aharou.core.ui

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
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
                var detailDepth by rememberSaveable { mutableIntStateOf(0) }
                val root = detailDepth == 0
                var count by rememberSaveable { mutableIntStateOf(0) }
                SideEffect { onRootChange(root) }
                BackHandler(enabled = !root) { detailDepth-- }
                Column(Modifier.fillMaxSize().testTag("root_$name")) {
                    Text("$name=$count", Modifier.testTag("count_$name").clickable { count++ })
                    Text("detail_$name", Modifier.testTag("detail_$name").clickable { detailDepth++ })
                    Text("root=$root", Modifier.testTag("state_$name"))
                    Text("depth=$detailDepth", Modifier.testTag("depth_$name"))
                    if (nested && !root) {
                        var panned by remember { mutableStateOf(false) }
                        var zoom by remember { mutableFloatStateOf(1f) }
                        val touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
                        Text(
                            "panned=$panned;zoom=$zoom;slop=$touchSlop",
                            Modifier.fillMaxWidth().height(100.dp).testTag("detail_canvas_$name")
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, factor, _ ->
                                        if (pan.x != 0f) panned = true
                                        zoom = (zoom * factor).coerceIn(.5f, 4f)
                                    }
                                },
                        )
                    }
                }
            })
        }
    }

    private fun pager() = compose.onNodeWithTag("primary_pages_pager")
    private fun tab(id: Int): SemanticsNodeInteraction {
        compose.onNodeWithTag("primary_pages_menu").performClick()
        return compose.onNodeWithText(compose.activity.getString(id))
    }
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

    @Test fun drawerNavigationAndRestorationRetainIndependentRootState() {
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

    @Test fun menuNavigationKeepsContentSwipeAvailable() {
        compose.setContent { Fixture() }
        tab(R.string.bottom_tab_config).performClick()
        expectRoute("config")
        compose.onNodeWithTag("primary_pages_menu").assertIsDisplayed()
        pager().performTouchInput { swipeLeft() }
        expectRoute("settings")
    }

    @Test fun subpagesAllowParentSwipeAndKeepClickNavigationAndBack() {
        compose.setContent { Fixture() }
        tab(R.string.bottom_tab_config).performClick()
        compose.onNodeWithTag("detail_config").performClick()
        pager().performTouchInput { swipeLeft() }; expectRoute("settings")
        compose.onNodeWithTag("detail_settings").performClick()
        pager().performTouchInput { swipeRight() }; expectRoute("config")
        compose.onNodeWithTag("state_config").assertTextEquals("root=false")
        pager().performTouchInput { swipeRight() }; expectRoute("projects")
        pager().performTouchInput { swipeLeft() }; expectRoute("config")
        compose.onNodeWithTag("state_config").assertTextEquals("root=false")
        tab(R.string.bottom_tab_settings).performClick(); expectRoute("settings")
        compose.onNodeWithTag("state_settings").assertTextEquals("root=false")
        tab(R.string.bottom_tab_config).performClick(); expectRoute("config")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("state_config").assertTextEquals("root=true")
        expectRoute("config")
        pager().performTouchInput { swipeLeft() }; expectRoute("settings")
        compose.onNodeWithTag("state_settings").assertTextEquals("root=false")
    }

    @Test fun nestedSettingPagesReturnOneLevelAtATime() {
        compose.setContent { Fixture() }
        tab(R.string.bottom_tab_config).performClick()
        val detail = compose.onNodeWithTag("detail_config")
        detail.performClick()
        detail.performClick()
        compose.onNodeWithTag("depth_config").assertTextEquals("depth=2")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("depth_config").assertTextEquals("depth=1")
        compose.onNodeWithTag("state_config").assertTextEquals("root=false")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("depth_config").assertTextEquals("depth=0")
        compose.onNodeWithTag("state_config").assertTextEquals("root=true")
        expectRoute("config")
    }

    @Test fun subpageTransformGestureWinsOverPrimarySwipe() {
        compose.setContent { Fixture(nested = true) }
        tab(R.string.bottom_tab_config).performClick()
        compose.onNodeWithTag("detail_config").performClick()
        compose.onNodeWithTag("detail_canvas_config").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("detail_canvas_config").assertTextContains("panned=true", substring = true)
        expectRoute("config")
        pager().performTouchInput { swipeLeft() }; expectRoute("settings")
    }

    @Test fun asymmetricTwoFingerTransformDoesNotDragTheParentPager() {
        compose.setContent { Fixture(nested = true) }
        tab(R.string.bottom_tab_config).performClick()
        compose.onNodeWithTag("detail_config").performClick()
        val canvas = compose.onNodeWithTag("detail_canvas_config")
        val initialLeft = compose.onNodeWithTag("root_config").getUnclippedBoundsInRoot().left.value
        canvas.performTouchInput {
            val slop = canvas.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString { it.text }.substringAfter("slop=").toFloat()
            val centerX = center.x
            val centerY = center.y
            down(0, Offset(centerX - 120f, centerY))
            down(1, Offset(centerX + 120f, centerY))
            updatePointerTo(0, Offset(centerX - 120f + slop * 1.5f, centerY))
            updatePointerTo(1, Offset(centerX + 120f - slop * .25f, centerY))
            move()
        }
        val firstPhase = canvas.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString { it.text }.substringAfter("zoom=").substringBefore(';').toFloat()
        val duringGestureLeft = compose.onNodeWithTag("root_config").getUnclippedBoundsInRoot().left.value
        assertEquals("An asymmetric first movement below centroid slop must not start zoom", 1f, firstPhase, 0.02f)
        assertTrue("The parent page must not move while both pointers are down", kotlin.math.abs(duringGestureLeft - initialLeft) < 1f)
        canvas.performTouchInput {
            val slop = canvas.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString { it.text }.substringAfter("slop=").substringBefore(';').toFloat()
            val centerX = center.x
            val centerY = center.y
            updatePointerTo(0, Offset(centerX - 120f - slop * 1.5f, centerY))
            updatePointerTo(1, Offset(centerX + 120f + slop * 1.5f, centerY))
            move()
        }
        val preReleaseScale = canvas.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString { it.text }.substringAfter("zoom=").substringBefore(';').toFloat()
        assertTrue("The child transform must respond while both pointers remain down", preReleaseScale > 1.05f)
        assertTrue("The parent page must not move before the transform gesture ends", kotlin.math.abs(
            compose.onNodeWithTag("root_config").getUnclippedBoundsInRoot().left.value - initialLeft
        ) < 1f)
        canvas.performTouchInput { up(0); up(1) }
        expectRoute("config")
        val finalScale = canvas.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString { it.text }.substringAfter("zoom=").substringBefore(';').toFloat()
        assertEquals(preReleaseScale, finalScale, 0.02f)
        assertTrue(kotlin.math.abs(compose.onNodeWithTag("root_config").getUnclippedBoundsInRoot().left.value - initialLeft) < 1f)
        expectRoute("config")
    }

    @Test fun nestedProjectPagerWinsInsideContentAndHeaderStillChangesPrimaryPage() {
        compose.setContent { Fixture(nested = true) }
        compose.onNodeWithTag("nested_projects").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("inner_1").assertIsDisplayed(); expectRoute("projects")
        compose.onNodeWithTag("project_header").performTouchInput { swipeLeft() }
        expectRoute("config")
    }

    @Test fun compactRootMenuReplacesTabsAndSelectsEveryPageAccessibly() {
        compose.setContent { Fixture() }
        compose.onNodeWithTag("primary_pages_tabs").assertDoesNotExist()
        compose.onNodeWithTag("primary_pages_rail").assertDoesNotExist()
        compose.onNodeWithTag("primary_pages_menu").assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals(compose.activity.getString(R.string.primary_navigation_open))
        assertTrue(
            "The menu row must not cover page content",
            compose.onNodeWithTag("primary_pages_menu").getUnclippedBoundsInRoot().bottom <=
                compose.onNodeWithTag("project_header").getUnclippedBoundsInRoot().top
        )
        for ((route, title) in listOf(
            "projects" to R.string.project_home,
            "config" to R.string.bottom_tab_config,
            "settings" to R.string.bottom_tab_settings,
        )) {
            compose.onNodeWithTag("primary_pages_menu").performClick()
            compose.onNodeWithText(compose.activity.getString(title)).performClick()
            expectRoute(route)
            compose.onNodeWithText(compose.activity.getString(title)).assertIsSelected()
            compose.onNodeWithTag("primary_pages_menu").assertIsDisplayed()
        }
        compose.onNodeWithTag("primary_pages_menu").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.primary_navigation_title)).assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(compose.activity.getString(R.string.primary_navigation_title)).assertDoesNotExist()
        compose.onNodeWithTag("primary_pages_menu").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.primary_navigation_title)).assertIsDisplayed()
    }

    @Test fun expandedRootRailReplacesMenuAndSelectsEveryPage() {
        val expandedConfiguration = Configuration(compose.activity.resources.configuration).apply { screenWidthDp = 900 }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides expandedConfiguration) { Fixture() }
        }
        compose.onNodeWithTag("primary_pages_tabs").assertDoesNotExist()
        compose.onNodeWithTag("primary_pages_menu").assertDoesNotExist()
        for ((route, title) in listOf(
            "projects" to R.string.project_home,
            "config" to R.string.bottom_tab_config,
            "settings" to R.string.bottom_tab_settings,
        )) {
            compose.onNodeWithText(compose.activity.getString(title)).performClick()
            expectRoute(route)
            compose.onNodeWithText(compose.activity.getString(title)).assertIsSelected()
            compose.onNodeWithTag("primary_pages_rail").assertIsDisplayed()
        }
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
