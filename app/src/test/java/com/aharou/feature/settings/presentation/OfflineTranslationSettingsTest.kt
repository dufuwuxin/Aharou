package com.aharou.feature.settings.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.core.translation.*
import com.aharou.feature.settings.presentation.component.OfflineTranslationSettings
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

class TranslationSettingsTestApplication : Application(), GeneratedComponentManager<Any> {
    val forward = MutableStateFlow(TranslationModelState.READY)
    val reverse = MutableStateFlow(TranslationModelState.READY)
    val service = mockk<OfflineTranslation>(relaxed = true) {
        every { enabled } returns MutableStateFlow(true)
        every { modelState } returns forward
        every { reverseModelState } returns reverse
    }
    private val component = object : GeneratedComponent, TranslationEntryPoint {
        override fun translation(): OfflineTranslation = service
    }
    override fun generatedComponent(): Any = component
}

@RunWith(RobolectricTestRunner::class)
@Config(application = TranslationSettingsTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OfflineTranslationSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun readyOnlyShowsCompactEntryAndDetailsRetainBothDirectionsAndRetry() {
        val app = compose.activity.application as TranslationSettingsTestApplication
        compose.setContent { MaterialTheme { OfflineTranslationSettings() } }
        compose.onNodeWithText(compose.activity.getString(R.string.translation_model)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.translation_attribution)).assertDoesNotExist()
        compose.onNodeWithTag("translation_settings_entry").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.translation_model)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.translation_reverse_model)).assertIsDisplayed()
        compose.runOnIdle { app.reverse.value = TranslationModelState.FAILED }
        compose.onNodeWithText(compose.activity.getString(R.string.translation_reverse_model)).performClick()
        compose.runOnIdle { verify { app.service.prepareModel(retry = true, target = "en") } }
    }
}
