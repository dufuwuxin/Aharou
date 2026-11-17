package com.aharou.feature.settings.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.feature.settings.data.repository.ContextPreferences
import com.aharou.feature.settings.data.repository.ContextPreset
import com.aharou.feature.settings.presentation.component.ContextManagementContent
import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationEntryPoint
import com.aharou.core.translation.TranslationModelState
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
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
class ContextManagementSectionTest {
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

    @After fun clearTranslationEntryPoint() { unmockkStatic(EntryPointAccessors::class) }

    @Test fun clickingPresetsAndCustomUsesRealCallbacksWithoutResettingCustom() {
        val state = mutableStateOf(ContextPreferences(rounds = 19))
        val selected = mutableListOf<ContextPreset>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ContextManagementContent(state.value, { preset ->
                        selected += preset
                        preset.preferences?.let { state.value = it }
                    }, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("context_rounds").assertDoesNotExist()
        compose.onNodeWithTag("context_custom").performClick()
        compose.onNodeWithTag("context_rounds").assertExists()
        compose.runOnIdle { assertEquals(19, state.value.rounds); assertEquals(emptyList<ContextPreset>(), selected) }
        for (preset in listOf(ContextPreset.ECONOMY, ContextPreset.DETAILED, ContextPreset.BALANCED)) {
            compose.onNodeWithTag("context_preset_${preset.id}").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(preset.preferences, state.value); assertEquals(preset, selected.last()) }
        }
    }

    @Test fun advancedInputRejectsNonNumericAndOutOfRangeThenSavesValidValue() {
        val saved = mutableListOf<Int>()
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ContextManagementContent(ContextPreferences(), {}, { saved += it }, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithTag("context_advanced").performClick()
        compose.onNodeWithTag("context_rounds").performScrollTo().performClick()
        for (invalid in listOf("abc", "101", "-1", "")) {
            compose.onNodeWithTag("context_number_input").performTextReplacement(invalid)
            compose.onNodeWithText(compose.activity.getString(R.string.common_save)).assertIsNotEnabled()
        }
        compose.onNodeWithTag("context_number_input").performTextReplacement("8")
        compose.onNodeWithText(compose.activity.getString(R.string.common_save)).performClick()
        compose.runOnIdle { assertEquals(listOf(8), saved) }
        compose.onNodeWithTag("context_rounds").performScrollTo().performClick()
        compose.onNodeWithTag("context_number_input").performTextReplacement("0")
        compose.onNodeWithText(compose.activity.getString(R.string.common_save)).performClick()
        compose.runOnIdle { assertEquals(listOf(8, 0), saved) }
    }
    @Test fun advancedThresholdAndToolsUseTheirOwnBounds() {
        val savedSoft = mutableListOf<Int>()
        val savedTools = mutableListOf<Int>()
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ContextManagementContent(ContextPreferences(), {}, {}, { savedSoft += it }, {}, {}, { savedTools += it })
            }
        } }
        compose.onNodeWithTag("context_custom").performClick()
        compose.onNodeWithTag("context_soft").performScrollTo().performClick()
        compose.onNodeWithTag("context_number_input").performTextReplacement("0")
        compose.onNodeWithText(compose.activity.getString(R.string.common_save)).assertIsNotEnabled()
        compose.onNodeWithTag("context_number_input").performTextReplacement("100")
        compose.onNodeWithText(compose.activity.getString(R.string.common_save)).performClick()
        compose.runOnIdle { assertEquals(listOf(100), savedSoft) }
        compose.onNodeWithTag("context_tools").performScrollTo().performClick()
        compose.onNodeWithTag("context_number_input").performTextReplacement("101")
        compose.onNodeWithText(compose.activity.getString(R.string.common_save)).assertIsNotEnabled()
        compose.onNodeWithTag("context_number_input").performTextReplacement("0")
        compose.onNodeWithText(compose.activity.getString(R.string.common_save)).performClick()
        compose.runOnIdle { assertEquals(listOf(0), savedTools) }
    }
}
