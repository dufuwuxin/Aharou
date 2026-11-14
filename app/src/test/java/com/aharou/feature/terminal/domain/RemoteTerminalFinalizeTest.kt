package com.aharou.feature.terminal.domain

import io.mockk.mockk
import io.mockk.every
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteTerminalFinalizeTest {
    @Test fun competingCompletionsEmitOnceAndUnknownCannotOverwriteConfirmedExit() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val modes = mockk<ExecutionModeHolder>()
            every { modes.mode } returns MutableStateFlow(ExecutionMode.REMOTE_SSH)
            val manager = RemoteTerminalSessionManager(mockk(relaxed = true), mockk(), modes, mockk())
            val tab = TerminalTab("fixture", "fixture", mockk(), isBackground = false, command = "fixture",
                notifyOnExit = true, runState = RunState.Running)
            RemoteTerminalSessionManager::class.java.getDeclaredField("_tabs").apply { isAccessible = true }
                .set(manager, MutableStateFlow(listOf(tab)))
            val events = mutableListOf<TabFinishedEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.tabFinishedEvents.collect { events += it } }
            val method = RemoteTerminalSessionManager::class.java.getDeclaredMethod("finalizeTab", String::class.java,
                Int::class.javaPrimitiveType, String::class.java, Continuation::class.java).apply { isAccessible = true }
            suspend fun finish(code: Int) = suspendCoroutine<Unit> { completion ->
                val result = method.invoke(manager, tab.id, code, "fixture tail", completion)
                if (result !== COROUTINE_SUSPENDED) completion.resume(Unit)
            }
            val first = launch { finish(7) }
            val second = launch { finish(-1) }
            first.join(); second.join()
            assertEquals(RunState.Finished(7), tab.runState)
            assertEquals(1, events.size)
            assertEquals(7, events.single().exitCode)
            events.clear()
            tab.runState = RunState.Running
            tab.finishedNotified = false
            finish(-1)
            finish(7)
            assertEquals(RunState.Finished(7), tab.runState)
            assertEquals(1, events.size)
            assertEquals(-1, events.single().exitCode)
        } finally { Dispatchers.resetMain() }
    }
}
