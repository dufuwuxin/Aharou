package com.aharou.feature.settings.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationEntryPoint
import com.aharou.core.translation.TranslationModelState
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerRuntimeBackend
import com.aharou.feature.agent.domain.container.RootfsSource
import com.aharou.feature.agent.domain.container.TawcrootRuntime
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.presentation.component.ActiveContainerRuntimeBackendSection
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
class ContainerRuntimeBackendRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun isolateSettingsAndRuntime() {
        val translation = mockk<OfflineTranslation>(relaxed = true)
        every { translation.enabled } returns MutableStateFlow(false)
        every { translation.modelState } returns MutableStateFlow(TranslationModelState.READY)
        val entryPoint = mockk<TranslationEntryPoint>()
        every { entryPoint.translation() } returns translation
        mockkStatic(EntryPointAccessors::class)
        every { EntryPointAccessors.fromApplication(any(), TranslationEntryPoint::class.java) } returns entryPoint
        mockkObject(TawcrootRuntime)
        every { TawcrootRuntime.availableForProfile(any(), any(), any()) } returns true
    }

    @After fun clearMocks() {
        unmockkObject(TawcrootRuntime)
        unmockkStatic(EntryPointAccessors::class)
    }

    @Test fun switchChangesOnlyTheActiveLocalProfileBackend() {
        var profile by mutableStateOf(ContainerProfile(
            id = "container-active", name = "Active", rootfsSource = RootfsSource.Asset("ubuntu"),
            shellPath = null, isBuiltin = true, runtimeBackend = ContainerRuntimeBackend.PROOT
        ))
        compose.setContent {
            AIEditorTheme {
                Column { ActiveContainerRuntimeBackendSection(profile) { profile = it } }
            }
        }

        val toggle = compose.onNodeWithTag("container_runtime_backend_switch")
        toggle.performClick()
        compose.runOnIdle { assertEquals(ContainerRuntimeBackend.TAWCROOT, profile.runtimeBackend) }
        toggle.performClick()
        compose.runOnIdle { assertEquals(ContainerRuntimeBackend.PROOT, profile.runtimeBackend) }
    }

    @Test fun remoteSshProfileDoesNotShowLocalRuntimeSwitch() {
        val remote = ContainerProfile(
            id = "remote-profile", name = "Remote", rootfsSource = RootfsSource.RemoteSsh("ssh-id", "/root"),
            shellPath = null, isBuiltin = false, mode = ExecutionMode.REMOTE_SSH,
            runtimeBackend = ContainerRuntimeBackend.PROOT
        )
        compose.setContent {
            AIEditorTheme { Column { ActiveContainerRuntimeBackendSection(remote) {} } }
        }
        compose.onNodeWithTag("container_runtime_backend_switch").assertDoesNotExist()
    }
}
