package com.aharou.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationEntryPoint
import com.aharou.core.translation.TranslationModelState
import com.aharou.feature.settings.data.local.ProviderLibraryPreferences
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteMember
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.presentation.component.ModelRouteGroupsSection
import com.aharou.feature.settings.presentation.component.ProvidersSection
import dagger.hilt.android.EntryPointAccessors
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
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
class NextSettingsPagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val provider = AIProviderConfig("p", "OpenAI", ProviderType.OPENAI, "fixture", baseUrl = "https://example.invalid", defaultModel = "first", models = listOf("first", "second"))

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

    @Test fun routeEditorSavesClickOrderAndCanSelectTheCreatedGroup() {
        val groups = MutableStateFlow(emptyList<ModelRouteGroup>())
        val selected = MutableStateFlow("")
        val model = mockk<ModelRouteGroupViewModel>()
        every { model.groups } returns groups
        every { model.readError } returns MutableStateFlow(false)
        every { model.selectedGroupId } returns selected
        var saved = emptyList<ModelRouteMember>()
        coEvery { model.create(any(), any()) } coAnswers {
            saved = secondArg()
            ModelRouteGroup("g", firstArg(), saved).also { groups.value = listOf(it) }
        }
        coEvery { model.select("g") } coAnswers { selected.value = "g"; groups.value.single() }
        compose.setContent { AIEditorTheme { ModelRouteGroupsSection(listOf(provider), {}, model) } }
        compose.onNodeWithTag("route_group_create").performClick()
        compose.onNodeWithTag("route_group_name").performTextInput("ordered")
        compose.onNodeWithText("OpenAI").performScrollTo().performClick()
        compose.onNodeWithTag("route_model_p_second").performScrollTo().performClick()
        compose.onNodeWithTag("route_model_p_first").performScrollTo().performClick()
        compose.onNodeWithTag("route_group_save").performClick()
        compose.waitForIdle()
        assertEquals(listOf(ModelRouteMember("p", "second"), ModelRouteMember("p", "first")), saved)
        compose.onNodeWithTag("route_group_select_g").performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { model.select("g") }
        compose.onNodeWithTag("route_group_select_g").assertIsNotEnabled()
    }

    @Test fun editingRepeatedRouteMembersKeepsDistinctSlotsAndRemovesOnlyOne() {
        val first = ModelRouteMember("p", "first")
        val second = ModelRouteMember("p", "second")
        val group = ModelRouteGroup("repeated", "weighted", listOf(first, second, first))
        val groups = MutableStateFlow(listOf(group))
        val model = mockk<ModelRouteGroupViewModel>()
        every { model.groups } returns groups
        every { model.readError } returns MutableStateFlow(false)
        every { model.selectedGroupId } returns MutableStateFlow("")
        var saved = emptyList<ModelRouteMember>()
        coEvery { model.update("repeated", any(), any()) } coAnswers {
            saved = thirdArg()
            group.copy(name = secondArg(), models = saved).also { groups.value = listOf(it) }
        }
        compose.setContent { AIEditorTheme { ModelRouteGroupsSection(listOf(provider), {}, model) } }
        compose.onNodeWithText("weighted").performClick()
        compose.onNodeWithText("1. first").assertIsDisplayed()
        compose.onNodeWithText("3. first").assertIsDisplayed()
        compose.onNodeWithTag("route_group_remove_2").performScrollTo().performClick()
        compose.onNodeWithTag("route_group_save").performClick()
        compose.waitForIdle()
        assertEquals(listOf(first, second), saved)
    }

    @Test fun pendingEditorIsMountedAfterTheManagementTabWasSelected() {
        val state = androidx.compose.runtime.mutableStateOf(ToolsUiState(loading = false))
        val tools = mockk<ToolsViewModel>(relaxed = true)
        val scripts = mockk<com.aharou.feature.settings.presentation.component.ScriptToolsViewModel>()
        every { scripts.definitions } returns MutableStateFlow(emptyList())
        compose.setContent { AIEditorTheme {
            com.aharou.feature.settings.presentation.component.UnifiedToolsSection(state.value, tools, scripts,
                false, {}, false, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("tools_tab_manage").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(editor = ToolEditorTarget()) }
        compose.onNodeWithText(compose.activity.getString(R.string.tools_editor_create_title)).assertIsDisplayed()
    }

    @Test fun providerBrandsAddConnectionAndHideWithoutDisablingTheProvider() {
        ProviderLibraryPreferences.initialize(compose.activity)
        ProviderLibraryPreferences.setHidden(emptySet())
        var added = ""
        compose.setContent { AIEditorTheme {
            ProvidersSection(listOf(provider), {}, {}, { _, _ -> }, { added = it.name })
        } }
        compose.onNodeWithTag("providers_show_hidden").performClick()
        compose.onNodeWithTag("providers_library_list").performScrollToKey("brand:openai")
        compose.onNodeWithTag("provider_visibility_openai").assertIsOn()
        compose.onNodeWithTag("providers_show_hidden").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("providers_library_search").performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithTag("providers_library_list").performScrollToKey("brand:openai")
        compose.onNodeWithTag("provider_brand_openai").performClick()
        compose.onNodeWithTag("provider_add_url_openai").performScrollTo().performClick()
        assertTrue(added.isNotBlank())
        compose.onNodeWithText(compose.activity.getString(R.string.providers_hide_type)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { "openai" in ProviderLibraryPreferences.hiddenTypes.value }
        assertTrue("openai" in ProviderLibraryPreferences.hiddenTypes.value)
        assertTrue(provider.isEnabled)
        compose.onNodeWithTag("providers_show_hidden").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("provider_brand_openai").performScrollTo().assertExists()
        ProviderLibraryPreferences.setHidden(emptySet())
    }
}
