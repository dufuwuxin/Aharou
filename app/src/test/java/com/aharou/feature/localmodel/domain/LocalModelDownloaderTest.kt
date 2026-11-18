package com.aharou.feature.localmodel.domain

import android.content.Context
import com.aharou.core.util.FileLogger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
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
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun spec(server: MockWebServer, bytes: ByteArray) = LocalModelSpec(
        id = "fixture", displayName = "fixture", fileName = "fixture.gguf",
        sizeBytes = bytes.size.toLong(),
        sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) },
        minRamGb = 1, urls = listOf(server.url("/model").toString()), contextSize = 128,
    )

    private fun withDownloader(block: (LocalModelDownloader) -> Unit) {
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { FileLogger.i(any(), any()) } just Runs
        try { block(LocalModelDownloader(mockk<Context>())) }
        finally { unmockkObject(FileLogger) }
    }

    @Test fun oversizedChunkedBodyNeverWritesPastExpectedSize() = withDownloader { downloader ->
        MockWebServer().use { server ->
            val expected = ByteArray(9000) { 7 }
            val model = spec(server, expected)
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(30000) { 7 }), 1024))
            val part = temporaryFolder.newFile()
            var largest = 0L
            val result = runCatching {
                runBlocking { downloader.download(model, part) { read, _ ->
                    largest = maxOf(largest, read)
                    assertTrue(part.length() <= model.sizeBytes)
                } }
            }
            assertTrue(result.exceptionOrNull() is IOException)
            assertTrue(part.length() <= model.sizeBytes)
            assertTrue(largest <= model.sizeBytes)
        }
    }

    @Test fun completeAndResumedBodiesKeepExactBytesAndHash() = withDownloader { downloader ->
        val bytes = "small verified model fixture".toByteArray()
        for (status in listOf(200, 206)) {
            MockWebServer().use { server ->
                val model = spec(server, bytes)
                val part = temporaryFolder.newFile().apply { writeBytes(bytes.copyOfRange(0, 5)) }
                val body = if (status == 206) bytes.copyOfRange(5, bytes.size) else bytes
                server.enqueue(MockResponse().setResponseCode(status).setBody(Buffer().write(body)))
                val result = runBlocking { downloader.download(model, part) { _, _ -> } }
                assertEquals(part, result)
                assertArrayEquals(bytes, result.readBytes())
                assertEquals(model.sha256, downloader.verifySha256(result))
                assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
            }
        }
    }

    @Test fun oversizedResumeAndShortBodyFailWithoutSilentSuccess() = withDownloader { downloader ->
        for (oversized in listOf(true, false)) {
            MockWebServer().use { server ->
                val bytes = "fixture".toByteArray()
                val model = spec(server, bytes)
                val part = temporaryFolder.newFile().apply { writeBytes(bytes.copyOfRange(0, 3)) }
                server.enqueue(MockResponse().setResponseCode(206)
                    .setChunkedBody(if (oversized) "too much data" else "x", 2))
                val result = runCatching { runBlocking { downloader.download(model, part) { _, _ -> } } }
                assertTrue(result.exceptionOrNull() is IOException)
                assertTrue(part.length() <= model.sizeBytes)
            }
        }
    }

    @Test fun satisfiedRangeRetainsCompleteFile() = withDownloader { downloader ->
        MockWebServer().use { server ->
            val bytes = "complete fixture".toByteArray()
            val part = temporaryFolder.newFile().apply { writeBytes(bytes) }
            val model = spec(server, bytes)
            server.enqueue(MockResponse().setResponseCode(416))
            runBlocking { downloader.download(model, part) { _, _ -> } }
            assertArrayEquals(bytes, part.readBytes())
        }
    }

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
