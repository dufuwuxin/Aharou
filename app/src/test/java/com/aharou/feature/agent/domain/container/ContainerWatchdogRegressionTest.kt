package com.aharou.feature.agent.domain.container

import com.aharou.core.util.ProcessIdentity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContainerWatchdogRegressionTest {
    @Test fun aDeadRootDoesNotDisableTheOutstandingExecutionDeadline() = runTest {
        val engine = LinuxContainerEngine(mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true))
        val process = mockk<Process>(relaxed = true)
        every { process.isAlive } returns false
        val timedOut = AtomicBoolean(false)
        val method = LinuxContainerEngine::class.java.getDeclaredMethod("launchKillWatchdog", CoroutineScope::class.java,
            Process::class.java, ProcessIdentity.Handle::class.java, java.lang.Long.TYPE,
            AtomicBoolean::class.java, String::class.java).apply { isAccessible = true }
        val watchdog = method.invoke(engine, this, process, null, 100L, timedOut, "fixture-only") as Job
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertTrue("An outstanding pipe reader still needs a deadline", timedOut.get())
        watchdog.join()
    }
}
