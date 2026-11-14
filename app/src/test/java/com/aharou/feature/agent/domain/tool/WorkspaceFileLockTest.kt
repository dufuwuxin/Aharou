package com.aharou.feature.agent.domain.tool

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WorkspaceFileLockTest {
    @Test fun cancelledSecondKeyWaitReleasesEveryReference() = runTest {
        val locks = WorkspaceFileLock()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch { locks.withLock("b") { entered.complete(Unit); release.await() } }
        entered.await()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock(listOf("a", "b")) { error("cancelled waiter entered") }
        }
        waiter.cancelAndJoin()
        assertEquals(1, locks.trackedKeyCount())
        release.complete(Unit)
        holder.join()
        assertEquals(0, locks.trackedKeyCount())
    }

    @Test fun releaseAndAcquireNeverProduceTwoActiveLocksForOneKey() = runBlocking {
        val locks = WorkspaceFileLock()
        val active = AtomicInteger()
        val count = AtomicInteger()
        coroutineScope {
            repeat(12) {
                launch(Dispatchers.Default) {
                    repeat(300) {
                        locks.withLock("file") {
                            assertEquals(1, active.incrementAndGet())
                            yield()
                            count.incrementAndGet()
                            assertEquals(0, active.decrementAndGet())
                        }
                    }
                }
            }
        }
        assertEquals(3600, count.get())
        assertEquals(0, locks.trackedKeyCount())
    }
}
