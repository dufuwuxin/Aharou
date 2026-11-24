package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.AgentTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequestLimiterTest {
    @Test fun queuedRequestWaitsForActiveRequestToFinish() = runTest {
        val limiter = ProviderRequestLimiter()
        limiter.setLimit("provider-a", 1)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val runRequest: suspend (CompletableDeferred<Unit>, CompletableDeferred<Unit>) -> Unit = { entered, release ->
            limiter.withPermit("provider-a") {
                active += 1
                peak = maxOf(peak, active)
                entered.complete(Unit)
                release.await()
                active -= 1
            }
        }

        val first = async { runRequest(firstEntered, releaseFirst) }
        firstEntered.await()
        val second = async { runRequest(secondEntered, CompletableDeferred(Unit)) }
        testScheduler.runCurrent()
        assertEquals(1, peak)
        assertTrue(!secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        secondEntered.await()
        second.await()
        first.await()
        assertEquals(1, peak)
    }

    @Test fun increasingLimitAdmitsMoreWorkAndDifferentProvidersDoNotShareSlots() = runTest {
        val limiter = ProviderRequestLimiter()
        val entered = mutableListOf<CompletableDeferred<Unit>>()
        val releases = mutableListOf<CompletableDeferred<Unit>>()
        val peaks = mutableMapOf<String, Int>()
        val active = mutableMapOf<String, Int>()
        suspend fun launchRequest(provider: String, limit: Int): kotlinx.coroutines.Job {
            limiter.setLimit(provider, limit)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            entered += started
            releases += release
            return async {
                limiter.withPermit(provider) {
                    active[provider] = (active[provider] ?: 0) + 1
                    peaks[provider] = maxOf(peaks[provider] ?: 0, active.getValue(provider))
                    started.complete(Unit)
                    release.await()
                    active[provider] = active.getValue(provider) - 1
                }
            }
        }

        val a1 = launchRequest("provider-a", 1)
        entered[0].await()
        limiter.setLimit("provider-a", 2)
        val a2 = launchRequest("provider-a", 2)
        entered[1].await()
        val b1 = launchRequest("provider-b", 1)
        entered[2].await()
        assertEquals(2, peaks["provider-a"])
        assertEquals(1, peaks["provider-b"])
        releases.forEach { it.complete(Unit) }
        listOf(a1, a2, b1).forEach { it.join() }
    }

    @Test fun separatelyConstructedAdaptersShareTheProviderLimit() = runTest {
        val entered = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val release = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        var callCount = 0
        val delegate = mockk<AIProvider>()
        coEvery { delegate.complete(any(), any(), any(), any()) } coAnswers {
            val index = callCount++
            entered[index].complete(Unit)
            release[index].await()
            AIResponse("ok")
        }
        val limiter = ProviderRequestLimiter()
        limiter.setLimit("provider-shared", 1)
        val firstAdapter = limiter.wrap("provider-shared", 1, delegate)
        val secondAdapter = limiter.wrap("provider-shared", 0, delegate)
        val messages = emptyList<AgentMessage>()
        val tools = emptyList<AgentTool>()
        suspend fun invokeAdapter(adapter: AIProvider) = adapter.complete("", messages, tools).content

        val first = async { invokeAdapter(firstAdapter) }
        entered[0].await()
        val second = async { invokeAdapter(secondAdapter) }
        testScheduler.runCurrent()
        assertEquals(1, callCount)
        assertTrue(!entered[1].isCompleted)
        release[0].complete(Unit)
        entered[1].await()
        release[1].complete(Unit)
        assertEquals("ok", first.await())
        assertEquals("ok", second.await())
    }

    @Test fun disablingLimitLetsQueuedAndFutureCallsProceedWithoutLeakingActiveSlots() = runTest {
        val limiter = ProviderRequestLimiter()
        limiter.setLimit("provider-a", 1)
        val entered = mutableListOf<CompletableDeferred<Unit>>()
        val releases = mutableListOf<CompletableDeferred<Unit>>()
        suspend fun launchRequest() = async {
            limiter.withPermit("provider-a") {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                entered += started
                releases += release
                started.complete(Unit)
                release.await()
            }
        }
        val first = launchRequest()
        testScheduler.runCurrent()
        entered.first().await()
        val second = launchRequest()
        testScheduler.runCurrent()
        assertEquals(1, entered.size)
        limiter.setLimit("provider-a", 0)
        testScheduler.runCurrent()
        assertEquals(2, entered.size)
        releases.forEach { it.complete(Unit) }
        first.join()
        second.join()
    }

    @Test fun cancellingStreamAdmitsNextRequestInQueue() = runTest {
        val limiter = ProviderRequestLimiter()
        val delegate = mockk<AIProvider>()
        val streaming = CompletableDeferred<Unit>()
        every { delegate.completeStream(any(), any(), any(), any()) } returns flow<AIStreamChunk> {
            streaming.complete(Unit)
            awaitCancellation()
        }
        val adapter = limiter.wrap("provider-a", 1, delegate)
        val stream = async { adapter.completeStream("", emptyList(), emptyList()).collect() }
        streaming.await()
        val order = mutableListOf<Int>()
        val first = async { limiter.withPermit("provider-a") { order += 1 } }
        val second = async { limiter.withPermit("provider-a") { order += 2 } }
        testScheduler.runCurrent()
        assertEquals(emptyList<Int>(), order)
        stream.cancel()
        first.await()
        second.await()
        assertEquals(listOf(1, 2), order)
    }

    @Test fun failedRequestReturnsItsPermit() = runTest {
        val limiter = ProviderRequestLimiter()
        limiter.setLimit("provider-a", 1)
        val failure = IllegalStateException("network failure")
        val outcome = runCatching { limiter.withPermit("provider-a") { throw failure } }
        assertSame(failure, outcome.exceptionOrNull())
        val next = async { limiter.withPermit("provider-a") { "next" } }
        testScheduler.runCurrent()
        assertTrue(next.isCompleted)
        assertEquals("next", next.await())
    }

    @Test fun cancellationAfterGrantButBeforeDispatchReturnsItsPermit() = runTest {
        val limiter = ProviderRequestLimiter()
        limiter.setLimit("provider-a", 1)
        val release = CompletableDeferred<Unit>()
        val first = async { limiter.withPermit("provider-a") { release.await() } }
        val granted = async { limiter.withPermit("provider-a") { error("request must not start") } }
        val next = async { limiter.withPermit("provider-a") { "next" } }
        testScheduler.runCurrent()
        assertFalse(granted.isCompleted)
        limiter.setLimit("provider-a", 2)
        granted.cancel()
        testScheduler.runCurrent()
        assertTrue(next.isCompleted)
        assertEquals("next", next.await())
        release.complete(Unit)
        first.await()
    }

    @Test fun cancelledWaiterDoesNotLeakASlot() = runTest {
        val limiter = ProviderRequestLimiter()
        limiter.setLimit("provider-a", 1)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            limiter.withPermit("provider-a") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val cancelled = async { limiter.withPermit("provider-a") { error("cancelled waiter must not enter") } }
        testScheduler.runCurrent()
        cancelled.cancel()
        cancelled.join()
        releaseFirst.complete(Unit)
        first.join()
        limiter.withPermit("provider-a") { assertTrue(true) }
    }
}
