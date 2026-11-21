package com.aharou.feature.plugin.domain.npm

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class NpmRegistryRepositoryTest {
    private val requests = mutableListOf<Request>()
    private var failure: IOException? = null
    private var respond = true
    private lateinit var lastCall: Call
    private var response: (Request) -> Response = { response(it, "{}") }
    private val repository = NpmRegistryRepository(Call.Factory { request ->
        requests += request
        FakeCall(request)
    })

    private inner class FakeCall(private val request: Request) : Call {
        private var executed = false
        private var canceled = false

        override fun request(): Request = request
        override fun execute(): Response = error("Expected enqueue()")
        override fun enqueue(callback: Callback) {
            check(!executed) { "Already Executed" }
            executed = true
            lastCall = this
            if (!respond) return
            val error = failure
            if (error != null) callback.onFailure(this, error)
            else callback.onResponse(this, response(request))
        }

        override fun cancel() { canceled = true }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = canceled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall(request)
    }

    @Test fun searchProjectsOnlyAllowedMetadataAndPreservesText() = runBlocking {
        response = { response(it, """{"objects":[{"package":{"name":"@s/pkg","version":"1.2.3",
          "description":"<script>untrusted</script>","license":"MIT","repository":{"url":"git+https://example.test/repo"},
          "deprecated":"old","dist":{"integrity":"sha512-value","tarball":"https://evil.test/code.tgz"},
          "scripts":{"install":"execute"},"bin":"execute","dependencies":{"evil":"*"}}}],"total":3}""") }
        val page = repository.search("pkg", size = 1)
        assertEquals(NpmPackageMetadata("@s/pkg", "1.2.3", "<script>untrusted</script>", "MIT",
            "git+https://example.test/repo", "old", "sha512-value"), page.packages.single())
        assertEquals(3, page.total)
        assertEquals(1, page.nextFrom)
        assertEquals(1, requests.size)
        assertEquals("GET", requests.single().method)
        assertEquals("application/json", requests.single().header("Accept"))
        assertNull(requests.single().body)
        assertNull(requests.single().header("Authorization"))
        assertNull(requests.single().header("Cookie"))
    }

    @Test fun exactMetadataNeverRequestsPackumentOrTarball() = runBlocking {
        response = { response(it, """{"name":"@s/pkg","version":"1.2.3","dist":{"tarball":"https://evil.test/a.tgz"},
          "scripts":{"install":"run"},"bin":"run","dependencies":{"x":"1"}}""") }
        val item = repository.packageVersion("@s/pkg", "1.2.3")
        assertEquals("@s/pkg", item.name)
        assertNull(item.integrity)
        assertEquals(listOf("@s/pkg", "1.2.3"), requests.single().url.pathSegments)
        assertEquals("registry.npmjs.org", requests.single().url.host)
        assertEquals(setOf("name", "version", "description", "license", "repository", "deprecated", "integrity"),
            NpmPackageMetadata::class.java.declaredFields
                .filterNot { it.isSynthetic || it.name == "\$stable" }
                .map { it.name }.toSet())
    }

    @Test fun missingOrWronglyTypedFieldsRemainNull() = runBlocking {
        response = { response(it, """{"name":null,"version":7,"description":{},"license":[],"repository":false,"deprecated":true,"dist":{"integrity":42}}""") }
        assertEquals(NpmPackageMetadata(null, null, null, null, null, null, null),
            repository.packageVersion("pkg", "1.0.0"))
        response = { response(it, """{"repository":"git://example.test/repo"}""") }
        assertEquals("git://example.test/repo", repository.packageVersion("pkg", "1.0.0").repository)
    }

    @Test fun emptyAndLastPagesDoNotInventTotalsOrNextPage() = runBlocking {
        response = { response(it, """{"objects":[]}""") }
        val empty = repository.search("pkg")
        assertNull(empty.total)
        assertNull(empty.nextFrom)
        response = { response(it, """{"objects":[{"package":{}}],"total":21}""") }
        assertNull(repository.search("pkg", 20, 1).nextFrom)
        assertNull(repository.search("pkg", 1000, 1).nextFrom)
    }

    @Test fun invalidInputNeverReachesTransport() = runBlocking {
        expect(NpmRegistryError.INVALID_INPUT) { repository.search(" ") }
        expect(NpmRegistryError.INVALID_INPUT) { repository.packageVersion("pkg", "latest") }
        expect(NpmRegistryError.INVALID_INPUT) { repository.packageVersion("https://evil.test", "1.0.0") }
        assertTrue(requests.isEmpty())
    }

    @Test fun redirectStatusesAreRejectedWithoutFollowingLocations() = runBlocking {
        for (code in listOf(301, 302, 303, 307, 308)) {
            response = { response(it, "", code).newBuilder().header("Location", "https://evil.test/a").build() }
            expect(NpmRegistryError.REDIRECT_REJECTED, code) { repository.search("pkg") }
        }
        assertEquals(5, requests.size)
    }

    @Test fun returnedCrossOriginOrDifferentPathIsRejected() = runBlocking {
        for (url in listOf("https://evil.test/x", "https://registry.npmjs.org/pkg/-/code.tgz")) {
            response = { response(it.newBuilder().url(url).build(), "{}") }
            expect(NpmRegistryError.REDIRECT_REJECTED, 200) { repository.packageVersion("pkg", "1.0.0") }
        }
    }

    @Test fun statusFailuresAreClassifiedWithoutRetry() = runBlocking {
        for (code in listOf(429, 400, 401, 403, 404, 500, 503)) {
            response = { response(it, "untrusted error body", code) }
            expect(if (code == 429) NpmRegistryError.RATE_LIMITED else NpmRegistryError.HTTP_ERROR, code) {
                repository.search("pkg")
            }
        }
        assertEquals(7, requests.size)
    }

    @Test fun sameUrlServiceUnavailableFollowUpUsesFinalResponse() = runBlocking {
        for ((code, body) in listOf(200 to "{\"objects\":[]}", 200 to "{", 503 to "")) {
            response = { request ->
                val prior = response(request, "", 503).newBuilder()
                    .header("Retry-After", "0").body(null).build()
                response(request, body, code).newBuilder().priorResponse(prior).build()
            }
            when {
                code == 503 -> expect(NpmRegistryError.HTTP_ERROR, 503) { repository.search("pkg") }
                body == "{" -> expect(NpmRegistryError.INVALID_RESPONSE) { repository.search("pkg") }
                else -> assertTrue(repository.search("pkg").packages.isEmpty())
            }
        }
    }

    @Test fun redirectInHistoryIsRejectedEvenWhenFinalUrlMatches() = runBlocking {
        response = { request ->
            val prior = response(request, "", 302).newBuilder().body(null).build()
            response(request, "{\"objects\":[]}").newBuilder().priorResponse(prior).build()
        }
        expect(NpmRegistryError.REDIRECT_REJECTED, 200) { repository.search("pkg") }
    }

    @Test fun malformedShapesAndOversizedSearchPageFailClearly() = runBlocking {
        for (body in listOf("not json", "[]", "null", "{}", "{\"objects\":{}}",
            "{\"objects\":[{}]}", "{\"objects\":[null]}", "{\"objects\":[{\"package\":{}},{\"package\":{}}]}")) {
            response = { response(it, body) }
            expect(NpmRegistryError.INVALID_RESPONSE) { repository.search("pkg", size = 1) }
        }
    }

    @Test fun nestedArraysAreAcceptedAtTheDepthLimitAndRejectedAboveIt() = runBlocking {
        fun body(arrays: Int) = "{\"objects\":[],\"extra\":" + "[".repeat(arrays) + "]".repeat(arrays) + "}"

        response = { response(it, body(127)) }
        assertTrue(repository.search("pkg").packages.isEmpty())
        response = { response(it, body(128)) }
        expect(NpmRegistryError.INVALID_RESPONSE) { repository.search("pkg") }
    }

    @Test fun quotedBracketsAndEscapesDoNotCountTowardTheDepthLimit() = runBlocking {
        val brackets = "{[".repeat(128)
        response = { response(it, """{"description":"quote: \"$brackets slash: \\"}""") }
        assertEquals("quote: \"$brackets slash: \\", repository.packageVersion("pkg", "1.0.0").description)
    }

    @Test fun advertisedAndUnknownLengthBodiesAreBoundedAndClosed() = runBlocking {
        for (length in listOf(NpmRegistryRepository.MAX_RESPONSE_BYTES + 1, -1L)) {
            var closed = false
            val source = object : okio.ForwardingSource(Buffer().write(ByteArray(1024 * 1024 + 1))) {
                override fun close() { closed = true; super.close() }
            }
            val body = object : ResponseBody() {
                private val buffered = source.buffer()
                override fun contentType() = null
                override fun contentLength() = length
                override fun source(): BufferedSource = buffered
            }
            response = { response(it, "").newBuilder().body(body).build() }
            expect(NpmRegistryError.RESPONSE_TOO_LARGE) { repository.packageVersion("pkg", "1.0.0") }
            assertTrue(closed)
        }
    }

    @Test fun timeoutAndNetworkFailureAreDistinct() = runBlocking {
        failure = SocketTimeoutException("fixture")
        expect(NpmRegistryError.TIMEOUT) { repository.search("pkg") }
        failure = IOException("fixture")
        expect(NpmRegistryError.NETWORK) { repository.search("pkg") }
    }

    @Test fun cancellationCancelsUnderlyingCall() = runBlocking {
        respond = false
        val pending = async(start = CoroutineStart.UNDISPATCHED) { repository.search("pkg") }
        pending.cancel()
        try { pending.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(lastCall.isCanceled())
    }

    @Test fun productionClientDropsOriginCredentialPathsAndEnforcesTimeouts() {
        // This test inspects client settings without making requests or initializing a trust manager.
        val original = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
            .addInterceptor { error("Must not execute") }
            .addNetworkInterceptor { error("Must not execute") }
            .authenticator { _, _ -> error("Must not authenticate") }
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl): List<Cookie> = error("Must not load cookies")
            }).build()
        val repo = NpmRegistryRepository(original)
        val field = NpmRegistryRepository::class.java.getDeclaredField("calls").apply { isAccessible = true }
        val client = field.get(repo) as OkHttpClient
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(original.proxySelector, client.proxySelector)
        assertSame(original.proxyAuthenticator, client.proxyAuthenticator)
        assertNull(client.cache)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(TimeUnit.SECONDS.toMillis(10).toInt(), client.connectTimeoutMillis)
        assertEquals(10_000, client.readTimeoutMillis)
        assertEquals(15_000, client.callTimeoutMillis)
    }

    private fun response(request: Request, text: String, code: Int = 200): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(text.toResponseBody()).build()

    private suspend fun expect(error: NpmRegistryError, status: Int? = null, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $error")
        } catch (e: NpmRegistryException) {
            assertEquals(error, e.error)
            assertEquals(status, e.statusCode)
            assertEquals(error.name.lowercase(), e.message)
        }
    }
}
