package com.aharou.feature.localmodel.domain

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class LocalModelDownloaderTest {
    @Test fun cancelAfterHeadersInterruptsBlockedBodyAndClosesIt() = runBlocking {
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS)) { "Call was not cancelled" }
                throw IOException("cancelled")
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.countDown() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        val call = mockk<Call>()
        every { call.cancel() } answers { cancelled.countDown() }
        val response = Response.Builder().request(Request.Builder().url("https://example.invalid").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        every { call.enqueue(any()) } answers { firstArg<Callback>().onResponse(call, response) }
        val job = launch(Dispatchers.IO) {
            try { withDownloadCall(call) { it.body!!.byteStream().read() } }
            catch (_: IOException) { /* The cancelled socket unblocks the body reader. */ }
        }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            verify(atLeast = 1) { call.cancel() }
        } finally {
            cancelled.countDown()
            job.cancelAndJoin()
        }
    }
}
