package com.aharou.feature.settings.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationEntryPoint
import com.aharou.core.translation.TranslationModelState
import com.aharou.feature.settings.presentation.component.ProviderConcurrencyLimitSection
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ProviderConcurrencyTestApplication : Application(), GeneratedComponentManager<Any> {
    private val translation = mockk<OfflineTranslation>(relaxed = true) {
        every { enabled } returns MutableStateFlow(false)
        every { modelState } returns MutableStateFlow(TranslationModelState.READY)
    }
    private val component = object : GeneratedComponent, TranslationEntryPoint {
        override fun translation(): OfflineTranslation = translation
    }
    override fun generatedComponent(): Any = component
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ProviderConcurrencyTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class ProviderConcurrencyLimitSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun rowOpensDialogAndToggleAndValidationRespectTheLimitBounds() {
        var limit by mutableIntStateOf(0)
        var changes = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIEditorTheme {
                Column {
                    ProviderConcurrencyLimitSection("provider-fixture", limit) {
                        limit = it
                        changes += 1
                    }
                }
            }
        }
        fun advanceFrame() = compose.mainClock.advanceTimeBy(1_000)
        fun dialogTitle() = compose.onNodeWithText("供应商并发上限")
        fun openFromRow() = compose.onNodeWithTag("provider_concurrency_row")
            .performSemanticsAction(SemanticsActions.OnClick) { action -> action() }
        fun saveValue(value: String) {
            compose.onNode(hasSetTextAction()).performTextReplacement(value)
            advanceFrame()
            compose.onNodeWithText("保存").performClick()
            advanceFrame()
        }

        advanceFrame()
        val toggle = compose.onNodeWithTag("provider_concurrency_toggle")
        toggle.assertIsOff()
        openFromRow()
        advanceFrame()
        dialogTitle().assertExists()
        compose.onNodeWithText("取消").performClick()
        advanceFrame()
        dialogTitle().assertDoesNotExist()
        assertEquals(0, limit)
        assertEquals(0, changes)

        toggle.performSemanticsAction(SemanticsActions.OnClick) { action -> action() }
        advanceFrame()
        dialogTitle().assertExists()
        compose.onNodeWithText("取消").performClick()
        advanceFrame()
        dialogTitle().assertDoesNotExist()
        assertEquals(0, changes)

        openFromRow()
        advanceFrame()
        saveValue("0")
        compose.onNodeWithText("请输入 1–32 的整数").assertExists()
        assertEquals(0, limit)
        assertEquals(0, changes)
        dialogTitle().assertExists()
        compose.onNode(hasSetTextAction()).assertTextEquals("0")
        compose.onNode(hasSetTextAction()).performTextReplacement("33")
        advanceFrame()
        compose.onNode(hasSetTextAction()).assertTextEquals("33")
        compose.onNodeWithText("请输入 1–32 的整数").assertDoesNotExist()
        compose.onNodeWithText("保存").performClick()
        advanceFrame()
        compose.onNode(hasSetTextAction()).assertTextEquals("33")
        compose.onNodeWithText("请输入 1–32 的整数").assertExists()
        assertEquals(0, limit)
        assertEquals(0, changes)
        compose.onNodeWithText("取消").performClick()
        advanceFrame()
        dialogTitle().assertDoesNotExist()
        openFromRow()
        advanceFrame()
        dialogTitle().assertExists()
        compose.onNode(hasSetTextAction()).assertTextEquals("4")
        compose.onNodeWithText("请输入 1–32 的整数").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        advanceFrame()
        dialogTitle().assertDoesNotExist()
        assertEquals(0, changes)
        openFromRow()
        advanceFrame()

        saveValue("1")
        assertEquals(1, limit)
        assertEquals(1, changes)
        dialogTitle().assertDoesNotExist()
        toggle.assertIsOn()

        toggle.performClick()
        assertEquals(0, limit)
        assertEquals(2, changes)
        advanceFrame()
        dialogTitle().assertDoesNotExist()
        openFromRow()
        advanceFrame()
        dialogTitle().assertExists()
        compose.onNodeWithText("取消").performClick()
        advanceFrame()
        dialogTitle().assertDoesNotExist()
        assertEquals(2, changes)

        openFromRow()
        advanceFrame()
        saveValue("32")
        assertEquals(32, limit)
        assertEquals(3, changes)
        dialogTitle().assertDoesNotExist()
        toggle.assertIsOn()
    }
}
