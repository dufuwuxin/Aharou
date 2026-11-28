package com.aharou.feature.settings.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationEntryPoint
import com.aharou.feature.agent.domain.container.ContainerDoctor
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.RootfsSource
import com.aharou.feature.agent.domain.container.TawcrootRuntime
import com.aharou.feature.settings.presentation.component.ContainerDoctorEntryPoint
import com.aharou.feature.settings.presentation.component.ContainerSection
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ContainerMountPickerTestApplication : Application(), GeneratedComponentManager<Any> {
    private val doctor = mockk<ContainerDoctor>(relaxed = true)
    private val translation = mockk<OfflineTranslation>(relaxed = true) {
        every { enabled } returns MutableStateFlow(false)
    }
    private val component = object : GeneratedComponent, ContainerDoctorEntryPoint, TranslationEntryPoint {
        override fun containerDoctor(): ContainerDoctor = doctor
        override fun translation(): OfflineTranslation = translation
    }

    override fun generatedComponent(): Any = component
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ContainerMountPickerTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class ContainerMountPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun isolateRuntime() {
        mockkObject(TawcrootRuntime)
        every { TawcrootRuntime.availableForProfile(any(), any(), any()) } returns true
    }

    @After fun clearMocks() {
        unmockkObject(TawcrootRuntime)
    }

    @Test fun mountEditorOffersSystemDirectorySelectionForLocalPath() {
        val profile = ContainerProfile(
            id = "custom-test", name = "Test container", rootfsSource = RootfsSource.Asset("ubuntu"),
            shellPath = "/bin/sh", extraBindings = listOf("/storage/emulated/0/old:/mnt/old"), isBuiltin = false
        )
        compose.setContent {
            AIEditorTheme {
                ContainerSection(
                    profiles = listOf(profile),
                    activeProfileId = profile.id,
                    onSelect = {},
                    onSaveCustom = {},
                    onEditCustom = {},
                    onDeleteProfile = {},
                    onRuntimeBackendChange = {}
                )
            }
        }

        compose.onNodeWithContentDescription("编辑").performClick()
        compose.onNodeWithText("选择本机目录").assertExists()
    }
}
