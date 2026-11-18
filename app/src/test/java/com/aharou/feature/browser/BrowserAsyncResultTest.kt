package com.aharou.feature.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BrowserAsyncResultTest {
    @Test fun timedOutAAndItsFinallyCannotCompleteOrClearB() = runTest {
        val results = BrowserAsyncResult()
        val a = results.begin()
        assertNull(withTimeoutOrNull(1) { a.result.await() })
        results.clear(a)
        val b = results.begin()
        assertNotEquals(a.id, b.id)
        assertEquals(b.id, UUID.fromString(b.id).toString())
        assertFalse(results.complete(a.id, "old A"))
        results.clear(a)
        assertFalse(b.result.isCompleted)
        assertTrue(results.complete(b.id, "B"))
        assertEquals("B", b.result.await())
        assertFalse(results.complete(b.id, "duplicate"))
    }

    @Test fun cancelledRequestAndLateRejectCannotChangeC() = runTest {
        val results = BrowserAsyncResult()
        val a = results.begin()
        a.result.cancel(CancellationException("fixture cancellation"))
        results.clear(a)
        val c = results.begin()
        assertFalse(results.complete(a.id, "old rejection"))
        results.clear(a)
        assertFalse(c.result.isCompleted)
        assertTrue(results.complete(c.id, "C"))
        assertEquals("C", c.result.await())
    }

    @Test fun wrapperBindsBothCallbacksToItsOwnUuid() {
        val request = BrowserAsyncResult().begin()
        val wrapped = request.wrap("(async function(){ return '中文'; })()")
        assertTrue(wrapped.contains("await ((async function(){ return '中文'; })())"))
        assertEquals(3, Regex("__aharou__\\.resolve\\('" + request.id + "',").findAll(wrapped).count())
        assertTrue(wrapped.contains("__aharou__.reject('${request.id}',"))
        assertTrue(wrapped.contains("String(__v__)"))
        assertTrue(request.wrap("Promise.resolve(null)", nullAsString = true).contains("resolve('${request.id}', 'null')"))
    }
}
