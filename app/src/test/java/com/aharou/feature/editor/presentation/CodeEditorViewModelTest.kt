package com.aharou.feature.editor.presentation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.editor.data.EditorSettings
import com.aharou.feature.editor.data.EditorSettingsRepository
import com.aharou.feature.editor.domain.TextMateSetup
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.sha256Fingerprint
import io.mockk.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class CodeEditorViewModelTest {
    @Test fun lateFirstReadCannotOverwriteNewerReadOfSamePath() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val firstReading = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val count = AtomicInteger()
        mockkObject(TextMateSetup)
        every { TextMateSetup.ensureInitialized(any()) } just Runs
        every { TextMateSetup.scopeNameFor(any()) } returns null
        val access = mockk<FileAccessProvider>(relaxed = true)
        every { access.checked() } returns access
        every { access.exists(any()) } returns true
        every { access.isFile(any()) } returns true
        every { access.fileSize(any()) } returns 3L
        every { access.toDisplayPath(any()) } answers { firstArg() }
        every { access.readBytes("/a") } answers {
            if (count.incrementAndGet() == 1) {
                firstReading.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                "old".toByteArray()
            } else "new".toByteArray()
        }
        every { access.readBytes("/b") } returns "bee".toByteArray()
        val settings = mockk<EditorSettingsRepository>()
        every { settings.settingsFlow } returns flowOf(EditorSettings())
        val versions = mockk<FileVersionRegistry>(relaxed = true)
        val vm = CodeEditorViewModel(ApplicationProvider.getApplicationContext(), access, settings, mockk(relaxed = true), versions)
        try {
            vm.load("/a")
            assertTrue(firstReading.await(5, TimeUnit.SECONDS))
            val field = CodeEditorViewModel::class.java.getDeclaredField("loadJob").apply { isAccessible = true }
            val oldJob = field.get(vm) as Job
            vm.load("/b")
            vm.load("/a")
            withTimeout(5000) {
                while ((vm.uiState.value as? EditorUiState.Success)?.content != "new") delay(10)
            }
            val version = vm.documentVersion.value
            releaseFirst.countDown()
            oldJob.join()
            assertEquals("new", (vm.uiState.value as EditorUiState.Success).content)
            assertEquals(version, vm.documentVersion.value)
            verify(exactly = 1) { versions.recordExternal("/a", sha256Fingerprint("new".toByteArray())) }
            verify(exactly = 0) { versions.recordExternal("/a", sha256Fingerprint("old".toByteArray())) }
            vm.load("/b")
            vm.load("/a")
            assertEquals("new", (vm.uiState.value as EditorUiState.Success).content)
        } finally {
            releaseFirst.countDown()
            unmockkObject(TextMateSetup)
            Dispatchers.resetMain()
        }
    }
}
