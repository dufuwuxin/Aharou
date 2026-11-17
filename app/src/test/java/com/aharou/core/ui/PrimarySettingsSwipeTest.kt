package com.aharou.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PrimarySettingsSwipeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun normalSwipesReportBothDirectionsWithoutLongPress() {
        val directions = mutableListOf<Boolean>()
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize().testTag("page").primarySettingsSwipe(true) { directions += it }) } }
        compose.onNodeWithTag("page").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("page").performTouchInput { swipeRight() }
        assertEquals(listOf(true, false), directions)
    }
    @Test fun disabledSubpageDoesNotNavigate() {
        var calls = 0
        compose.setContent { Box(Modifier.fillMaxSize().testTag("page").primarySettingsSwipe(false) { calls++ }) }
        compose.onNodeWithTag("page").performTouchInput { swipeLeft() }
        assertEquals(0, calls)
    }
}
