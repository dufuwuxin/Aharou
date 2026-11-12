package com.aharou.feature.agent.domain.memory

import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class CloudMemoryTransportTest {
    private fun transport(timeoutMs: Long = 20000, block: suspend (MockWebServer, CloudMemorySearch) -> Unit) = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        val original = CloudMemorySearch.productionClient()
        assertFalse(original.followRedirects)
        assertFalse(original.followSslRedirects)
        assertFalse(original.retryOnConnectionFailure)
        assertEquals(20000, original.callTimeoutMillis)
        val client = original.newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val settings = mockk<MemorySyncSettings>()
        every { settings.cloudSearchEnabled } returns true
        every { settings.cloudSearchEndpoint } returns server.url("/query").toString()
        try {
            block(server, CloudMemorySearch(settings, client))
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    @Test fun realHttpsSendsOnlyTheExplicitQueryAndNeverFollowsRedirects() = transport { server, search ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"results\":[]}"))
        assertTrue(search.search("explicit query", 3).containsKey("results"))
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals(setOf("query", "limit"), Json.parseToJsonElement(request.body.readUtf8()).jsonObject.keys)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect")))
        server.enqueue(MockResponse().setBody("{\"results\":[]}"))
        try {
            search.search("redirect must fail")
            fail("Redirect must not be followed")
        } catch (failure: CloudMemorySearch.Failure) {
            assertEquals("CLOUD_REDIRECT_REJECTED", failure.code)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun decodedGzipAndUnknownLengthBodiesStillRespectTheByteLimit() = transport { server, search ->
        val bytes = Buffer()
        GzipSink(bytes).buffer().use { it.writeUtf8("x".repeat(1_048_577)) }
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(bytes))
        try {
            search.search("gzip")
            fail("Decoded body exceeds limit")
        } catch (failure: CloudMemorySearch.Failure) {
            assertEquals("CLOUD_BODY_TOO_LARGE", failure.code)
        }
        server.enqueue(MockResponse().setChunkedBody("x".repeat(1_048_577), 8192))
        try {
            search.search("chunked")
            fail("Unknown-length body exceeds limit")
        } catch (failure: CloudMemorySearch.Failure) {
            assertEquals("CLOUD_BODY_TOO_LARGE", failure.code)
        }
    }

    @Test fun realCallTimeoutProducesASpecificFailure() = transport(timeoutMs = 200) { server, search ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try {
            search.search("slow")
            fail("Call must time out")
        } catch (failure: CloudMemorySearch.Failure) {
            assertEquals("CLOUD_TIMEOUT", failure.code)
        }
    }

    @Test fun cancellationStopsARealBlockedReadAndDoesNotPoisonTheNextCall() = transport { server, search -> coroutineScope {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { search.search("cancel") }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        server.enqueue(MockResponse().setBody("{\"results\":[]}"))
        assertTrue(search.search("after cancellation").containsKey("results"))
    } }
}
