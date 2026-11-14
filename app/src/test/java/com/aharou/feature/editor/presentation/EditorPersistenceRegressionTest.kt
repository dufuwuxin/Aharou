package com.aharou.feature.editor.presentation

import android.content.Context
import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.editor.data.EditorSettingsRepository
import com.aharou.feature.editor.data.EditorSettings
import com.aharou.feature.editor.domain.TextMateSetup
import com.aharou.feature.editor.lsp.EditorLspManager
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.SafeFileAccessProvider
import com.aharou.feature.workspace.domain.sha256Fingerprint
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditorPersistenceRegressionTest {
    @Before fun before() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun after() { Dispatchers.resetMain(); unmockkObject(TextMateSetup) }

    private fun viewModel(access: FileAccessProvider): CodeEditorViewModel {
        mockkObject(TextMateSetup)
        every { TextMateSetup.ensureInitialized(any()) } just Runs
        every { TextMateSetup.scopeNameFor(any()) } returns null
        val settings = mockk<EditorSettingsRepository>()
        every { settings.settingsFlow } returns MutableStateFlow(EditorSettings())
        return CodeEditorViewModel(mockk<Context>(relaxed = true), access, settings,
            mockk<EditorLspManager>(relaxed = true), mockk<FileVersionRegistry>(relaxed = true))
    }

    @Test fun checkedRemoteFailureCannotBecomeSaveSuccess() = runBlocking {
        val backend = mockk<FileAccessProvider>(relaxed = true)
        every { backend.checked() } returns backend
        every { backend.writeFile(any(), any(), any(), any()) } throws IOException("fixture write failure")
        val model = viewModel(SafeFileAccessProvider(backend))
        model.save("note.txt", "unsaved draft")
        val event = withTimeout(5_000) { model.saveEvents.first() }
        assertTrue("A failed remote write must remain an error: $event", event is SaveResult.Error)
    }

    @Test fun displayedOldBytesCannotAcquireANewerDiskFingerprint() = runBlocking {
        val backend = mockk<FileAccessProvider>(relaxed = true)
        var disk = "old"
        every { backend.checked() } returns backend
        every { backend.exists(any()) } returns true
        every { backend.isFile(any()) } returns true
        every { backend.fileSize(any()) } returns 3
        every { backend.readBytes(any()) } answers { "old".toByteArray().also { disk = "external" } }
        every { backend.fingerprint(any()) } answers { sha256Fingerprint(disk.toByteArray()) }
        every { backend.readFile(any()) } answers { disk }
        every { backend.toDisplayPath(any()) } answers { firstArg() }
        every { backend.writeFile(any(), any(), any(), any()) } answers { disk = arg(1) }
        val model = viewModel(backend)
        model.load("note.txt")
        withTimeout(5_000) { model.uiState.first { it is EditorUiState.Success } }
        model.save("note.txt", "mine")
        val event = withTimeout(5_000) { model.saveEvents.first() }
        assertTrue("The external revision was never displayed", event is SaveResult.Conflict)
        assertEquals("external", disk)
    }
}
