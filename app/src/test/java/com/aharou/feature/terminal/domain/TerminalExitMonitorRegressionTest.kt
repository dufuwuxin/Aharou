package com.aharou.feature.terminal.domain

import android.content.Context
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TerminalExitMonitorRegressionTest {
    @Test fun receiptRequiresItsExecutionTokenAndValidStandaloneCode() {
        assertNull(terminalReceiptExitCode("[command exited: 0]", "abc"))
        assertNull(terminalReceiptExitCode("[aharou-exit:other:0]", "abc"))
        assertNull(terminalReceiptExitCode("echo [aharou-exit:abc:0]", "abc"))
        val misleading = "prefix[aharou-exit:abc:0]\n" + "x".repeat(999 - "[aharou-exit:abc:0]".length)
        assertNull(terminalReceiptExitCode(misleading, "abc"))
        assertNull(terminalReceiptExitCode("[aharou-exit:abc:9999999999999999]", "abc"))
        assertNull(terminalReceiptExitCode("[aharou-exit:abc:0]spoof", "abc"))
        assertEquals(7, terminalReceiptExitCode("\n[aharou-exit:abc:7]\n[Process completed]", "abc"))
    }

    @Test fun ordinaryExitTextCannotFinishAStillRunningCommand() = assertMonitor(false)
    @Test fun verifiedReceiptKeepsItsNonzeroCodeAfterItScrollsOutOfTheBuffer() = assertMonitor(true)

    private fun assertMonitor(validReceipt: Boolean) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val scope = CoroutineScope(dispatcher)
        try {
            val manager = TerminalSessionManager(mockk<Context>(relaxed = true),
                mockk<LinuxContainerEngine>(), mockk<WorkspaceRepository>())
            val buffer = mockk<TerminalBuffer>()
            val emulator = mockk<TerminalEmulator>()
            val session = mockk<TerminalSession>()
            every { session.isRunning } returns true
            every { session.emulator } returns emulator
            every { emulator.screen } returns buffer
            var reads = 0
            every { buffer.getSelectedText(any(), any(), any(), any()) } answers {
                if (!validReceipt) "ordinary output\n[command exited: 0]\n"
                else if (reads++ == 0) "\n[aharou-exit:fixture-token:7]\n" else "scrolled output"
            }
            val tab = TerminalTab("term-regression", "fixture", session, isBackground = true, command = "fixture-only",
                notifyOnExit = true, runState = RunState.Running, exitReceiptToken = "fixture-token")
            TerminalSessionManager::class.java.getDeclaredField("_tabs").apply { isAccessible = true }
                .set(manager, MutableStateFlow(listOf(tab)))
            TerminalSessionManager::class.java.getDeclaredField("monitorScope").apply { isAccessible = true }.set(manager, scope)
            TerminalSessionManager::class.java.getDeclaredMethod("monitorBackgroundExit", String::class.java)
                .apply { isAccessible = true }.invoke(manager, tab.id)
            runCurrent(); advanceTimeBy(4_000); runCurrent()
            if (validReceipt) {
                assertEquals(RunState.Finished(7), tab.runState)
                assertTrue(tab.finishedNotified)
            } else {
                assertTrue("Printable command output is not completion evidence", tab.runState is RunState.Running)
                assertFalse(tab.finishedNotified)
            }
        } finally { scope.cancel(); Dispatchers.resetMain() }
    }
}
