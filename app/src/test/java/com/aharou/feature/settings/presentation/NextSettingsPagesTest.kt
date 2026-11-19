package com.aharou.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.core.theme.AIEditorTheme
import com.aharou.feature.settings.data.local.ProviderLibraryPreferences
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteMember
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.presentation.component.ModelRouteGroupsSection
import com.aharou.feature.settings.presentation.component.ProvidersSection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
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

    @Test fun routeEditorSavesClickOrderAndCanSelectTheCreatedGroup() {
        val groups = MutableStateFlow(emptyList<ModelRouteGroup>())
        val selected = MutableStateFlow("")
        val model = mockk<ModelRouteGroupViewModel>()
        every { model.groups } returns groups
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
        compose.onNodeWithTag("provider_brand_openai").performScrollTo().performClick()
        compose.onNodeWithTag("provider_add_url_openai").performScrollTo().performClick()
        assertTrue(added.isNotBlank())
        compose.onNodeWithText(compose.activity.getString(R.string.providers_hide_type)).performClick()
        compose.waitForIdle()
        assertTrue("openai" in ProviderLibraryPreferences.hiddenTypes.value)
        assertTrue(provider.isEnabled)
        compose.onNodeWithTag("providers_show_hidden").performClick()
        compose.onNodeWithTag("provider_brand_openai").performScrollTo().assertExists()
        ProviderLibraryPreferences.setHidden(emptySet())
    }
}
