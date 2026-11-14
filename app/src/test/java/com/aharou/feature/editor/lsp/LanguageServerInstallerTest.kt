package com.aharou.feature.editor.lsp

import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LanguageServerInstallerTest {
    private val commands = mockk<CommandEngine>()
    private val vendored = mockk<VendoredLanguageServers> {
        every { downloadProgress } returns MutableStateFlow(emptyMap())
        every { recoveryRequired } returns MutableStateFlow(emptySet())
    }
    private val installer = LanguageServerInstaller(commands, mockk(), vendored)

    @Test fun explicitInstallationDelegatesLockedBootstrapAndVerifiesInstalledVersion() = runBlocking {
        coEvery { vendored.install(LanguageExtension.PYTHON) } returns CommandResult("installed", 0)
        every { vendored.activeProgram(any()) } returns "/extensions/bin/pylsp"
        every { vendored.rootForCurrentBackend() } returns "/extensions"
        every { vendored.installedVersion(any()) } returns "1.2.3"
        coEvery { commands.runCommandSyncIfReady(any(), any(), any()) } returns CommandResult("ready", 0)
        assertTrue(installer.install(LanguageExtension.PYTHON).success)
        coVerifyOrder { vendored.install(LanguageExtension.PYTHON); commands.runCommandSyncIfReady(any(), any(), any()) }
    }

    @Test fun initializationFailureIsActionableAndDoesNotDownloadArtifacts() = runBlocking {
        coEvery { vendored.install(LanguageExtension.PYTHON) } throws IllegalStateException("SSH connection refused")
        val result = installer.install(LanguageExtension.PYTHON)
        assertFalse(result.success)
        assertEquals("SSH connection refused", (result.status as ExtensionStatus.Unavailable).reason)
        coVerify(exactly = 0) { commands.runCommandSyncIfReady(any(), any(), any()) }
    }

    @Test fun cancellationIsNotAnInstallFailureAndDoesNotDownload() = runBlocking {
        coEvery { vendored.install(LanguageExtension.PYTHON) } throws CancellationException("cancelled")
        try { installer.install(LanguageExtension.PYTHON); fail("cancelled install succeeded") }
        catch (_: CancellationException) { }
        coVerify(exactly = 0) { commands.runCommandSyncIfReady(any(), any(), any()) }
    }

    @Test fun readOnlyProbeNeverInitializesContainer() = runBlocking {
        every { vendored.activeProgram(any()) } returns null
        every { commands.notReadyHint() } returns "initialize backend"
        assertEquals("initialize backend", (installer.probe(LanguageExtension.PYTHON) as ExtensionStatus.Unavailable).reason)
        coVerify(exactly = 0) { commands.ensureInstalled() }
    }
}
