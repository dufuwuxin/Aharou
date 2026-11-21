package com.aharou.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationEntryPoint
import com.aharou.core.translation.TranslationModelState
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteMember
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.presentation.component.ModelRouteGroupsSection
import dagger.hilt.android.EntryPointAccessors
import io.mockk.coEvery
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
class RouteProviderExpansionTest {
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

    @Test fun providerTogglesPreserveOrderedSelectionsAndRepeatedSlots() {
        val first = ModelRouteMember("p", "first")
        val second = ModelRouteMember("p", "second")
        val third = ModelRouteMember("q", "third")
        val group = ModelRouteGroup("g", "weighted", listOf(first, first))
        val alpha = AIProviderConfig("p", "Alpha", ProviderType.OPENAI, "fixture", baseUrl = "https://example.invalid",
            defaultModel = "first", models = listOf("first", "second"))
        val beta = alpha.copy(id = "q", name = "Beta", defaultModel = "third", models = listOf("third"))
        val viewModel = mockk<ModelRouteGroupViewModel>()
        every { viewModel.groups } returns MutableStateFlow(listOf(group))
        every { viewModel.readError } returns MutableStateFlow(false)
        every { viewModel.selectedGroupId } returns MutableStateFlow("")
        var saved = emptyList<ModelRouteMember>()
        coEvery { viewModel.update(group.id, any(), any()) } coAnswers {
            saved = thirdArg()
            group.copy(models = saved)
        }
        compose.setContent { AIEditorTheme { ModelRouteGroupsSection(listOf(alpha, beta), {}, viewModel) } }
        compose.onNodeWithText(group.name).performClick()

        val alphaHeader = compose.onNode(hasText(alpha.name) and hasClickAction())
        val betaHeader = compose.onNode(hasText(beta.name) and hasClickAction())
        val alphaModel = compose.onNodeWithTag("route_model_p_second")
        val betaModel = compose.onNodeWithTag("route_model_q_third")
        alphaModel.assertDoesNotExist()
        betaModel.assertDoesNotExist()
        compose.onNodeWithText("1. first").assertIsDisplayed()
        compose.onNodeWithText("2. first").assertIsDisplayed()

        betaHeader.performClick()
        alphaModel.assertDoesNotExist()
        betaModel.performClick()
        alphaHeader.performClick()
        alphaModel.performClick()
        alphaHeader.performClick()
        alphaModel.assertDoesNotExist()
        betaModel.assertIsSelected()
        alphaHeader.performClick()
        alphaModel.assertIsSelected()
        alphaHeader.performClick()
        betaHeader.performClick()
        alphaModel.assertDoesNotExist()
        betaModel.assertDoesNotExist()

        compose.onNodeWithTag("route_group_save").performClick()
        compose.waitForIdle()
        assertEquals(listOf(first, first, third, second), saved)
    }
}
