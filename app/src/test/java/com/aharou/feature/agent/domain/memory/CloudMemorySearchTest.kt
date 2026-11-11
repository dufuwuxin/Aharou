package com.aharou.feature.agent.domain.memory

import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.memory.CloudMemorySearchTool
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class CloudMemorySearchTest {
    private val settings = mockk<MemorySyncSettings> {
        every { cloudSearchEnabled } returns true
        every { cloudSearchEndpoint } returns "https://memory.example.invalid/search"
    }
    private val factory = FixtureFactory()
    private val client = CloudMemorySearch(settings, factory)
    private val tool = CloudMemorySearchTool(client)

    @Test fun realRequestContainsOnlyExplicitQueryAndLimitAndResultsStayUntrusted() = runBlocking {
        factory.body = """{"results":[{"title":"external","content":"reference text","source":"/not/a/local/path","confidence":1}]}"""
        val result = tool.execute(mapOf("query" to JsonPrimitive("explicit query"), "ignored" to JsonPrimitive("private")))
        assertTrue(result is ToolResult.Success)
        val request = factory.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://memory.example.invalid/search", request.url.toString())
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val payload = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(setOf("query", "limit"), payload.keys)
        assertEquals(JsonPrimitive("explicit query"), payload["query"])
        assertEquals(JsonPrimitive(5), payload["limit"])
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        val data = (result as ToolResult.Success).data.jsonObject
        val hit = data.getValue("results").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(false), hit["verified"])
        assertEquals(JsonPrimitive("reference"), hit["kind"])
        assertEquals(JsonPrimitive("external_claim"), hit["sourceTrust"])
        assertEquals(JsonNull, hit["confidence"])
        assertEquals(JsonPrimitive(1), hit["externalConfidence"])
        assertEquals(JsonPrimitive("/not/a/local/path"), hit["source"])
    }

    @Test fun disabledMissingAndInvalidEndpointsNeverCreateRequests() = runBlocking {
        every { settings.cloudSearchEnabled } returns false
        assertError("CLOUD_SEARCH_DISABLED")
        every { settings.cloudSearchEnabled } returns true
        every { settings.cloudSearchEndpoint } returns ""
        assertError("CLOUD_ENDPOINT_MISSING")
        listOf("http://memory.example.invalid", "https://u:p@memory.example.invalid", "https://@memory.example.invalid", "https://memory.example.invalid/#fragment",
            "https://memory.example.invalid/?token=secret", "not-a-url", "https:\\memory.example.invalid").forEach { endpoint ->
            every { settings.cloudSearchEndpoint } returns endpoint
            assertError("INVALID_ENDPOINT")
        }
        assertTrue(factory.requests.isEmpty())
    }

    @Test fun invalidQueryAndLimitAreRejectedWithoutNetwork() = runBlocking {
        listOf(JsonPrimitive(""), JsonPrimitive("x".repeat(4097)), JsonPrimitive(123), JsonNull,
            buildJsonObject { put("private", true) }).forEach { query ->
            val result = tool.execute(mapOf("query" to query))
            assertEquals("INVALID_QUERY", (result as ToolResult.Error).code)
        }
        assertEquals("INVALID_QUERY", (tool.execute(emptyMap()) as ToolResult.Error).code)
        listOf(JsonPrimitive(0), JsonPrimitive(21), JsonPrimitive("5"), JsonPrimitive(1.5), JsonNull).forEach { limit ->
            assertEquals("INVALID_LIMIT", (tool.execute(mapOf("query" to JsonPrimitive("q"), "limit" to limit)) as ToolResult.Error).code)
        }
        assertTrue(factory.requests.isEmpty())
    }

    @Test fun endpointHttpErrorsAndRedirectsAreSpecificFailures() = runBlocking {
        factory.status = 503
        assertError("CLOUD_HTTP_ERROR")
        factory.status = 302
        assertError("CLOUD_REDIRECT_REJECTED")
        assertEquals(2, factory.requests.size)
    }

    @Test fun timeoutAndIoFailureAreSpecificErrors() = runBlocking {
        factory.failure = SocketTimeoutException("fixture")
        assertError("CLOUD_TIMEOUT")
        factory.failure = IOException("fixture")
        assertError("CLOUD_NETWORK_ERROR")
    }

    @Test fun malformedJsonMissingFieldsAndTooManyResultsAreRejected() = runBlocking {
        listOf("broken", "[]", "{}", "{\"results\":[{}]}",
            """{"results":[{"title":"t","content":"c","source":"s","confidence":"1"}]}""").forEach {
            factory.body = it
            assertError("CLOUD_BAD_JSON")
        }
        factory.body = "{\"results\":[" + List(21) { "{}" }.joinToString(",") + "]}"
        assertError("CLOUD_TOO_MANY_RESULTS")
        factory.body = "{\"results\":[{},{}]}"
        assertError("CLOUD_TOO_MANY_RESULTS", 1)
    }

    @Test fun declaredAndStreamingBodiesAndFieldTotalsAreBounded() = runBlocking {
        factory.body = "x".repeat(1_048_577)
        assertError("CLOUD_BODY_TOO_LARGE")
        factory.unknownLength = true
        assertError("CLOUD_BODY_TOO_LARGE")
        factory.unknownLength = false
        factory.body = """{"results":[{"title":"t","content":"${"x".repeat(32_001)}","source":"s"}]}"""
        assertError("CLOUD_FIELDS_TOO_LARGE")
        val hit = """{"title":"t","content":"${"x".repeat(26_000)}","source":"s"}"""
        factory.body = "{\"results\":[" + List(4) { hit }.joinToString(",") + "]}"
        assertError("CLOUD_FIELDS_TOO_LARGE")
    }

    @Test fun cancellationCancelsActualCallAndPropagatesRatherThanReturningAnError() = runBlocking {
        factory.deferred = true
        var returned = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            tool.execute(mapOf("query" to JsonPrimitive("q")))
            returned = true
        }
        assertEquals(1, factory.requests.size)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(factory.lastCall!!.isCanceled())
        assertFalse(returned)
    }

    @Test fun permissionContractIsReadOnlyExplicitAndDiscoverable() {
        assertEquals(setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.NETWORK_READ), tool.capabilities)
        assertEquals(ToolPermissionPolicy.ASK, tool.permissionPolicy)
        assertTrue(tool.deferredLoading)
        assertFalse(tool.acceptsSecrets)
        assertEquals("memory_cloud_search", tool.name)
    }

    private suspend fun assertError(code: String, limit: Int = 5) {
        val result = tool.execute(mapOf("query" to JsonPrimitive("q"), "limit" to JsonPrimitive(limit)))
        assertTrue(result.toString(), result is ToolResult.Error)
        assertEquals(code, (result as ToolResult.Error).code)
    }

    /** Real OkHttp Request/Response, no sockets, dependencies, repository or credentials. */
    private class FixtureFactory : Call.Factory {
        var body = "{\"results\":[]}"
        var status = 200
        var failure: IOException? = null
        var deferred = false
        var unknownLength = false
        val requests = mutableListOf<Request>()
        var lastCall: FixtureCall? = null
        override fun newCall(request: Request): Call {
            requests += request
            return FixtureCall(request).also { lastCall = it }
        }

        inner class FixtureCall(private val request: Request) : Call {
            private var cancelled = false
            private var executed = false
            override fun request(): Request = request
            override fun execute(): Response = error("Only asynchronous production transport is expected")
            override fun enqueue(responseCallback: Callback) {
                executed = true
                if (deferred) return
                failure?.let { responseCallback.onFailure(this, it); return }
                val responseBody = if (unknownLength) object : ResponseBody() {
                    private val source = Buffer().writeUtf8(body)
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): BufferedSource = source
                } else body.toResponseBody("application/json".toMediaType())
                responseCallback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(status).message("fixture").body(responseBody).build())
            }
            override fun cancel() { cancelled = true }
            override fun isExecuted() = executed
            override fun isCanceled() = cancelled
            override fun timeout() = Timeout.NONE
            override fun clone(): Call = FixtureCall(request)
        }
    }
}
