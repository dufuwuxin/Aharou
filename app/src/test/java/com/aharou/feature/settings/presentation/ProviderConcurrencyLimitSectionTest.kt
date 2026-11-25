package com.aharou.feature.settings.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationEntryPoint
import com.aharou.core.translation.TranslationModelState
import com.aharou.feature.settings.presentation.component.ProviderConcurrencyLimitSection
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp")
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class ProviderConcurrencyLimitSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun isolateTranslationEntryPoint() {
        val translation = mockk<OfflineTranslation>(relaxed = true)
        every { translation.enabled } returns MutableStateFlow(false)
        every { translation.modelState } returns MutableStateFlow(TranslationModelState.READY)
        val entryPoint = mockk<TranslationEntryPoint>()
        every { entryPoint.translation() } returns translation
        mockkStatic(EntryPointAccessors::class)
        every { EntryPointAccessors.fromApplication(any(), TranslationEntryPoint::class.java) } returns entryPoint
    }

    @After fun clearTranslationEntryPoint() = unmockkStatic(EntryPointAccessors::class)

    @Test fun enablingShowsInlineLimitAndInvalidInputDoesNotChangeTheSavedValue() {
        var limit by mutableIntStateOf(0)
        compose.setContent {
            AIEditorTheme {
                Column {
                    ProviderConcurrencyLimitSection("provider-fixture", limit) { limit = it }
                }
            }
        }
        compose.onNodeWithTag("provider_concurrency_row").assertHasNoClickAction()
        val toggle = compose.onNodeWithTag("provider_concurrency_toggle")
        val field = compose.onNodeWithTag("provider_concurrency_input")
        toggle.assertIsOff().performClick()
        field.assertDoesNotExist()
        toggle.assertIsOn()
        field.performTextReplacement("12")
        compose.runOnIdle { assertEquals(12, limit) }
        compose.runOnIdle { limit = 7 }
        field.assertTextContains("7")
        field.performTextReplacement("")
        compose.onNodeWithText("请输入 1–32 的整数").assertExists()
        compose.runOnIdle { assertEquals(7, limit) }
        field.performTextReplacement("33")
        compose.onNodeWithText("请输入 1–32 的整数").assertExists()
        compose.runOnIdle { assertEquals(7, limit) }
        toggle.performClick()
        field.assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, limit) }
        toggle.assertIsOff()
    }
}
