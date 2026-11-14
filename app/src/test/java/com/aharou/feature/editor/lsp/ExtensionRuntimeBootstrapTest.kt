package com.aharou.feature.editor.lsp

import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExtensionRuntimeBootstrapTest {
    private val commands = mockk<CommandEngine>()

    @Test fun selectedBackendCannotRedirectCapturedProvisioningCommand() = runBlocking {
        var selected = "original"
        val captured = selected
        val destinations = mutableListOf<String>()
        val pinned = pinnedExtensionCommands(commands) { command, timeout ->
            assertEquals(ExtensionRuntimeBootstrap.SCRIPT, command)
            assertEquals(600_000L, timeout)
            destinations += captured
            CommandResult("ready", 0)
        }
        var checks = 0
        try {
            ExtensionRuntimeBootstrap.prepare(pinned) {
                if (++checks == 1) selected = "other"
                else check(selected == captured) { "EXTENSION_BACKEND_CHANGED" }
            }
            fail("backend change was accepted")
        } catch (failure: IllegalStateException) {
            assertEquals("EXTENSION_BACKEND_CHANGED", failure.message)
        }
        assertEquals(listOf("original"), destinations)
        coVerify(exactly = 0) { commands.runCommandSyncIfReady(any(), any(), any()) }
    }

    @Test fun backendChangeBeforeRuntimePreventsAnyCommand() = runBlocking {
        try {
            ExtensionRuntimeBootstrap.prepare(commands) { error("EXTENSION_BACKEND_CHANGED") }
            fail("changed backend accepted")
        } catch (failure: IllegalStateException) {
            assertEquals("EXTENSION_BACKEND_CHANGED", failure.message)
        }
        coVerify(exactly = 0) { commands.runCommandSyncIfReady(any(), any(), any()) }
    }

    @Test fun realExitCodeIsPreservedAndRetryRunsAgain() = runBlocking {
        coEvery { commands.runCommandSyncIfReady(any(), any(), any()) } returnsMany listOf(
            CommandResult("apt failed", 100), CommandResult("ready", 0),
        )
        assertEquals(100, ExtensionRuntimeBootstrap.prepare(commands) {}?.exitCode)
        assertEquals(0, ExtensionRuntimeBootstrap.prepare(commands) {}?.exitCode)
        coVerify(exactly = 2) { commands.runCommandSyncIfReady(ExtensionRuntimeBootstrap.SCRIPT, null, 600_000L) }
    }

    @Test fun backendChangeDuringRuntimeRejectsResult() = runBlocking {
        coEvery { commands.runCommandSyncIfReady(any(), any(), any()) } returns CommandResult("ready", 0)
        var checks = 0
        try {
            ExtensionRuntimeBootstrap.prepare(commands) { check(++checks == 1) { "EXTENSION_BACKEND_CHANGED" } }
            fail("changed backend accepted")
        } catch (failure: IllegalStateException) {
            assertEquals("EXTENSION_BACKEND_CHANGED", failure.message)
        }
        assertEquals(2, checks)
    }

    @Test fun commandCancellationPropagates() = runBlocking {
        coEvery { commands.runCommandSyncIfReady(any(), any(), any()) } throws CancellationException("cancelled")
        try {
            ExtensionRuntimeBootstrap.prepare(commands) {}
            fail("cancelled bootstrap accepted")
        } catch (_: CancellationException) { }
    }

    @Test fun profileChangeDuringInitializationStopsBeforeRuntimeAndNetwork() = runBlocking {
        val profiles = mockk<ContainerProfileResolver>()
        val initial = ContainerProfile.BUILTIN_UBUNTU
        every { profiles.forWorkspace(null) } returns initial
        coEvery { commands.ensureInstalled() } answers {
            every { profiles.forWorkspace(null) } returns initial.copy(id = "changed")
        }
        val vendored = VendoredLanguageServers(mockk(), mockk(), commands, profiles, mockk())
        try {
            vendored.install(LanguageExtension.PYTHON)
            fail("changed profile accepted")
        } catch (failure: IllegalStateException) {
            assertEquals("EXTENSION_BACKEND_CHANGED", failure.message)
        }
        coVerify(exactly = 0) { commands.runCommandSyncIfReady(any(), any(), any()) }
    }
}
