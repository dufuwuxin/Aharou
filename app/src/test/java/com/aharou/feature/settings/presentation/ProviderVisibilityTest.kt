package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationEntryPoint
import com.aharou.core.translation.TranslationModelState
import com.aharou.feature.settings.data.local.ProviderLibraryPreferences
import com.aharou.feature.settings.data.local.ProviderPreset
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.presentation.component.ProvidersSection
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
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w411dp-h891dp")
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class ProviderVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun isolateTranslationEntryPoint() {
        val translation = mockk<OfflineTranslation>(relaxed = true)
        every { translation.enabled } returns MutableStateFlow(false)
        every { translation.modelState } returns MutableStateFlow(TranslationModelState.READY)
        val entryPoint = mockk<TranslationEntryPoint>()
        every { entryPoint.translation() } returns translation
        mockkStatic(EntryPointAccessors::class)
        every { EntryPointAccessors.fromApplication(any(), TranslationEntryPoint::class.java) } returns entryPoint
        ProviderLibraryPreferences.initialize(compose.activity)
    }

    @After fun resetVisibilityAndTranslation() {
        ProviderLibraryPreferences.setHidden(emptySet())
        unmockkStatic(EntryPointAccessors::class)
    }

    @Test fun visibilityManagerRestoresHiddenBrandsAndPreservesConfiguredConnections() {
        val hiddenBefore = setOf("openai", "unlisted_brand")
        ProviderLibraryPreferences.setHidden(hiddenBefore)
        val provider = AIProviderConfig("fixture", "OpenAI fixture", ProviderType.OPENAI, "fixture", baseUrl = "https://example.invalid",
            defaultModel = "fixture-model", models = listOf("fixture-model"))
        var added: ProviderPreset? = null
        var edited: AIProviderConfig? = null
        compose.setContent { AIEditorTheme {
            ProvidersSection(listOf(provider), { edited = it }, { error("Visibility must not delete a connection") },
                { _, _ -> error("Visibility must not reorder connections") }, { added = it })
        } }

        val search = compose.onNodeWithTag("providers_library_search")
        search.performTextInput("unmatched-fixture-query")
        compose.onNodeWithTag("provider_brand_openai").assertDoesNotExist()
        val manage = compose.onNodeWithTag("providers_show_hidden")
        manage.performClick()
        val list = compose.onNodeWithTag("providers_library_list")
        list.performScrollToKey("brand:openai")
        val openAiSwitch = compose.onNodeWithTag("provider_visibility_openai")
        openAiSwitch.assertIsOff().performClick()
        list.performScrollToKey("brand:deepseek")
        compose.onNodeWithTag("provider_visibility_deepseek").assertIsOn().performClick()
        val hiddenAfter = setOf("deepseek", "unlisted_brand")
        compose.waitUntil(timeoutMillis = 5_000) { ProviderLibraryPreferences.hiddenTypes.value == hiddenAfter }
        assertEquals(hiddenAfter, compose.activity.applicationContext.getSharedPreferences("provider_library", Context.MODE_PRIVATE)
            .getStringSet("hidden_types", emptySet()))
        list.performScrollToKey("brand:openai")
        openAiSwitch.assertIsOn()

        manage.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("provider_visibility_openai").assertDoesNotExist()
        compose.onNodeWithTag("provider_brand_openai").assertExists()
        search.performTextClearance()
        list.performScrollToKey("brand:openai")
        compose.onNodeWithTag("provider_brand_openai").performClick()
        list.performScrollToKey("brand-actions:openai")
        compose.onNodeWithTag("provider_add_url_openai").performClick()
        compose.runOnIdle { assertEquals("OpenAI", added?.name) }
        list.performScrollToKey(provider.id)
        compose.onNode(hasText(provider.name) and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(provider, edited) }
    }

    @Test fun inPageAddOpensCustomProviderWithBlankFields() {
        var added: ProviderPreset? = null
        compose.setContent { AIEditorTheme {
            ProvidersSection(emptyList(), {}, {}, { _, _ -> }, { added = it })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.settings_add_provider)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_custom)).performClick()
        compose.runOnIdle {
            assertEquals("", added?.name)
            assertEquals("", added?.baseUrl)
            assertEquals(ProviderType.OPENAI.name, added?.type)
        }
    }
}
