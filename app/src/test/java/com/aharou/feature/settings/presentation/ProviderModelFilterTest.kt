package com.aharou.feature.settings.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationEntryPoint
import com.aharou.core.translation.TranslationModelState
import com.aharou.feature.settings.presentation.component.FetchModelsDialog
import com.aharou.feature.settings.presentation.component.ProviderEditorOptionsGroup
import com.aharou.feature.settings.presentation.component.filterProviderModelCandidates
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.presentation.FetchState
import org.junit.Assert.assertEquals
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
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
class ProviderEditorOptionsGroupTest {
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

    @Test fun candidateSearchMatchesWithoutCaseAndEmptyQueryRestoresAll() {
        val candidates = listOf("Claude-3.7-Sonnet", "gpt-4.1", "Gemini-2.5-Pro")

        assertEquals(listOf("Claude-3.7-Sonnet"), filterProviderModelCandidates(candidates, "CLAUDE"))
        assertEquals(emptyList<String>(), filterProviderModelCandidates(candidates, "missing"))
        assertEquals(emptyList<String>(), filterProviderModelCandidates(listOf("deepseek-v4-flash"), "claude"))
        assertEquals(candidates, filterProviderModelCandidates(candidates, ""))
    }

    @Test fun searchFiltersVisibleCandidatesWithoutChangingSelectedOrCustomValues() {
        val selectedModel = mutableStateOf("gpt-4.1")
        val customModel = mutableStateOf("my-custom-model")
        val added = mutableListOf<String>()
        compose.setContent {
            AIEditorTheme {
                Column {
                    androidx.compose.material3.Text("selected=${selectedModel.value}")
                    androidx.compose.material3.Text("custom=${customModel.value}")
                    FetchModelsDialog(
                        fetchState = FetchState.Success(listOf("gpt-4.1", "claude-3.7")),
                        providerId = "openai",
                        modelMetadata = emptyMap<String, ModelMetadata>(),
                        existingModels = emptyList(),
                        onFetchModels = {},
                        onAddModel = { added += it },
                        onDismiss = {}
                    )
                }
            }
        }

        compose.onNodeWithText("gpt-4.1", substring = true).assertExists()
        compose.onNodeWithText("claude-3.7").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(com.aharou.R.string.provider_filter_models_hint))
            .performClick().performTextInput("CLAUDE")
        compose.onNodeWithText("gpt-4.1", substring = true).assertDoesNotExist()
        compose.onNodeWithText("claude-3.7").assertExists()
        compose.onNodeWithText("selected=gpt-4.1").assertExists()
        compose.onNodeWithText("custom=my-custom-model").assertExists()
        compose.runOnIdle { assertEquals(emptyList<String>(), added) }
    }

    @Test fun onboardingFallbackShowsNoMatchesAfterFilteringErrorState() {
        compose.setContent {
            AIEditorTheme {
                FetchModelsDialog(
                    fetchState = FetchState.Error("no key"),
                    providerId = "deepseek",
                    modelMetadata = emptyMap(),
                    existingModels = emptyList(),
                    onFetchModels = {},
                    onAddModel = {},
                    onDismiss = {},
                    isOnboarding = true
                )
            }
        }

        compose.onNodeWithText("deepseek-v4-flash").assertExists()
        compose.onNodeWithText(compose.activity.getString(com.aharou.R.string.provider_filter_models_hint))
            .performClick().performTextInput("not-a-model")
        compose.onNodeWithText("deepseek-v4-flash").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(com.aharou.R.string.provider_no_matching_models)).assertExists()
    }

    @Test fun optionsAreCollapsedInitiallyAndToggleWithoutDroppingContent() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            AIEditorTheme {
                Column {
                    ProviderEditorOptionsGroup(
                        expanded = expanded,
                        onToggle = { expanded = !expanded }
                    ) {
                        androidx.compose.material3.Text("Provider option fixture")
                    }
                }
            }
        }

        compose.onNodeWithText("Provider option fixture").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(com.aharou.R.string.provider_section_options))
            .assertExists()
            .performClick()
        compose.onNodeWithText("Provider option fixture").assertExists()
        compose.onNodeWithText(compose.activity.getString(com.aharou.R.string.provider_section_options))
            .performClick()
        compose.onNodeWithText("Provider option fixture").assertDoesNotExist()
    }
}
