package com.aharou.feature.settings.presentation

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import com.aharou.R
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationEntryPoint
import com.aharou.core.translation.TranslationModelState
import com.aharou.feature.agent.domain.container.ContainerDoctor
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.RootfsSource
import com.aharou.feature.agent.domain.container.TawcrootRuntime
import com.aharou.feature.settings.presentation.component.ContainerDoctorEntryPoint
import com.aharou.feature.settings.presentation.component.ProfileEditSheet
import com.aharou.feature.settings.presentation.component.isAccessibleMountDirectory
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp")
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class ContainerMountPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // Substitute only the external activity; keep the real contract and Compose result callback.
    private val registry = object : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var launchedRequest = -1
        var launchedAction: String? = null
        var failLaunch = false

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            if (failLaunch) throw android.content.ActivityNotFoundException()
            launchedRequest = requestCode
            launchedAction = contract.createIntent(RuntimeEnvironment.getApplication(), input).action
        }
    }
    private var saved: ContainerProfile? = null

    @Before fun isolateSettingsAndRuntime() {
        val translation = mockk<OfflineTranslation>(relaxed = true) {
            every { enabled } returns MutableStateFlow(false)
            every { modelState } returns MutableStateFlow(TranslationModelState.READY)
        }
        val translationEntryPoint = mockk<TranslationEntryPoint>()
        every { translationEntryPoint.translation() } returns translation
        val doctorEntryPoint = mockk<ContainerDoctorEntryPoint>()
        every { doctorEntryPoint.containerDoctor() } returns mockk<ContainerDoctor>(relaxed = true)
        mockkStatic(EntryPointAccessors::class)
        mockkStatic(Environment::class)
        every { Environment.isExternalStorageManager() } returns true
        every { EntryPointAccessors.fromApplication(any(), TranslationEntryPoint::class.java) } returns translationEntryPoint
        every { EntryPointAccessors.fromApplication(any(), ContainerDoctorEntryPoint::class.java) } returns doctorEntryPoint
        mockkObject(TawcrootRuntime)
        every { TawcrootRuntime.availableForProfile(any(), any(), any()) } returns true
    }

    @After fun clearMocks() {
        unmockkObject(TawcrootRuntime)
        unmockkStatic(EntryPointAccessors::class)
        unmockkStatic(Environment::class)
    }

    @Test fun canceledDirectoryPickerLeavesExistingPathsUnchanged() {
        openEditor()
        launchPicker()
        compose.runOnIdle { registry.dispatchResult(registry.launchedRequest, Activity.RESULT_CANCELED, null) }

        field(LOCAL).assertExists()
        field(GUEST).assertExists()
        save()
        compose.runOnIdle { assertEquals(listOf("$LOCAL:$GUEST"), saved?.extraBindings) }
    }

    @Test fun unavailableDirectoryPickerLeavesExistingPathsUnchanged() {
        openEditor()
        compose.runOnIdle { registry.failLaunch = true }
        compose.onNodeWithContentDescription("编辑").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.container_mount_pick_local))
            .performScrollTo().performClick()

        field(LOCAL).assertExists()
        save()
        compose.runOnIdle { assertEquals(listOf("$LOCAL:$GUEST"), saved?.extraBindings) }
    }

    @Test fun selectedDirectoryUpdatesOnlyLocalPathAndIsSaved() {
        val root = File(compose.activity.cacheDir, "mount-picker").apply { mkdirs() }
        val selectedDirectory = File(root, "Picked").apply { mkdirs() }
        every { Environment.getExternalStorageDirectory() } returns root
        val pickedPath = selectedDirectory.absolutePath
        openEditor()
        launchPicker()
        returnDirectory("com.android.externalstorage.documents", "primary:Picked")

        field(pickedPath).assertExists()
        field(GUEST).assertExists()
        save()
        compose.runOnIdle { assertEquals(listOf("$pickedPath:$GUEST"), saved?.extraBindings) }
    }

    @Test fun missingDirectoryDoesNotReplaceBinding() {
        val root = File(compose.activity.cacheDir, "missing-mount").apply { mkdirs() }
        every { Environment.getExternalStorageDirectory() } returns root
        openEditor()
        launchPicker()
        returnDirectory("com.android.externalstorage.documents", "primary:Missing")

        field(LOCAL).assertExists()
        save()
        compose.runOnIdle { assertEquals(listOf("$LOCAL:$GUEST"), saved?.extraBindings) }
    }

    @Test fun nonDirectoryDoesNotReplaceBinding() {
        val root = File(compose.activity.cacheDir, "file-mount").apply { mkdirs() }
        File(root, "NotDirectory").writeText("file")
        every { Environment.getExternalStorageDirectory() } returns root
        openEditor()
        launchPicker()
        returnDirectory("com.android.externalstorage.documents", "primary:NotDirectory")

        field(LOCAL).assertExists()
        save()
        compose.runOnIdle { assertEquals(listOf("$LOCAL:$GUEST"), saved?.extraBindings) }
    }

    @Test fun directoryWithoutContainerStorageAccessDoesNotReplaceBinding() {
        every { Environment.isExternalStorageManager() } returns false
        openEditor()
        launchPicker()
        returnDirectory("com.android.externalstorage.documents", "primary:Picked")

        field(LOCAL).assertExists()
        save()
        compose.runOnIdle { assertEquals(listOf("$LOCAL:$GUEST"), saved?.extraBindings) }
    }

    @Test fun nonLocalProviderDoesNotReplaceTheBindingSource() {
        openEditor()
        launchPicker()
        returnDirectory("example.documents", "primary:opaque")

        field(LOCAL).assertExists()
        save()
        compose.runOnIdle { assertEquals(listOf("$LOCAL:$GUEST"), saved?.extraBindings) }
    }

    @Test fun mountSourceMustExistBeDirectoryAndReadable() {
        assertEquals(false, isAccessibleMountDirectory("/missing", isDirectory = false, canRead = false))
        assertEquals(false, isAccessibleMountDirectory("/file", isDirectory = false, canRead = true))
        assertEquals(false, isAccessibleMountDirectory("/unreadable", isDirectory = true, canRead = false))
        assertEquals(true, isAccessibleMountDirectory("/directory", isDirectory = true, canRead = true))
    }

    @Test fun manualPathStillUsesExistingValidationAndSave() {
        openEditor()
        field(LOCAL).performScrollTo().performTextReplacement("relative/path")
        save()
        compose.runOnIdle { assertNull(saved) }
        val error = checkNotNull(ContainerProfile.validateBinding("relative/path:$GUEST"))
        compose.onNodeWithText(error).assertExists()

        field("relative/path").performScrollTo().performTextReplacement("/custom/local")
        save()
        compose.runOnIdle { assertEquals(listOf("/custom/local:$GUEST"), saved?.extraBindings) }
    }

    private fun openEditor() {
        val profile = ContainerProfile(
            id = "custom-test", name = "Test container", rootfsSource = RootfsSource.Asset("ubuntu"),
            shellPath = "/bin/sh", extraBindings = listOf("$LOCAL:$GUEST"), isBuiltin = false
        )
        compose.setContent {
            AIEditorTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                    ProfileEditSheet(
                        initial = profile,
                        onDismiss = {},
                        onConfirm = { edited, _ -> saved = edited }
                    )
                }
            }
        }
    }

    private fun launchPicker() {
        compose.onNodeWithContentDescription("编辑").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.container_mount_pick_local))
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, registry.launchedAction) }
    }

    private fun returnDirectory(authority: String, docId: String) {
        val uri = DocumentsContract.buildTreeDocumentUri(authority, docId)
        compose.runOnIdle {
            registry.dispatchResult(registry.launchedRequest, Activity.RESULT_OK, Intent().setData(uri))
        }
    }

    private fun field(value: String) = compose.onNode(hasSetTextAction() and hasText(value))

    private fun save() = compose.onNodeWithText(compose.activity.getString(R.string.common_save)).performClick()

    private companion object {
        const val LOCAL = "/sdcard/old"
        const val GUEST = "/mnt/old"
    }
}
