package com.aharou.feature.plugin

import com.aharou.feature.plugin.runtime.HostPluginHttpInterceptor
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HostPluginHttpInterceptorTest {
    @Test fun frameReaderPreservesDoneCommentsAndOnlyTransformsConsumedFrames() {
        var calls = 0
        val upstream = Buffer().writeUtf8(": keepalive\nevent: token\ndata: {\"text\":\"old\"}\n\ndata: [DONE]\n\n")
        val source = HostPluginHttpInterceptor.EventSource(upstream) { calls++; "{\"text\":\"new\"}" }
        val sink = Buffer()
        assertTrue(source.read(sink, 1) > 0)
        assertEquals(1, calls)
        while (source.read(sink, 8192) != -1L) { }
        val result = sink.readUtf8()
        assertTrue(result.contains(": keepalive\nevent: token"))
        assertTrue(result.contains("data: {\"text\":\"new\"}"))
        assertTrue(result.endsWith("data: [DONE]\n\n"))
        assertEquals(1, calls)
        source.close()
    }

    @Test fun realHttpConsumerChangesModelBodiesButLeavesLoginAlone() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"answer\":1}"))
            server.enqueue(MockResponse().setBody("ok"))
            val client = OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).addInterceptor(HostPluginHttpInterceptor(
                { it.replace("original", "changed") }, { it.replace("1", "2") },
            )).build()
            val json = "application/json".toMediaType()
            val request = Request.Builder().url(server.url("/v1/chat/completions"))
                .post("{\"model\":\"m\",\"messages\":[],\"tag\":\"original\"}".toRequestBody(json)).build()
            client.newCall(request).execute().use { assertEquals("{\"answer\":2}", it.body!!.string()) }
            assertTrue(server.takeRequest().body.readUtf8().contains("changed"))
            val login = "{\"login\":\"original\"}"
            client.newCall(Request.Builder().url(server.url("/login")).post(login.toRequestBody(json)).build()).execute().close()
            assertEquals(login, server.takeRequest().body.readUtf8())
        }
    }

    @Test fun hookErrorsAndMalformedResultsPreservePayload() {
        assertEquals("{}", HostPluginHttpInterceptor.applyHook("{}") { error("fixture") })
        assertEquals("{}", HostPluginHttpInterceptor.applyHook("{}") { "invalid" })
    }
    @Test fun prettyHookToolArgumentsAndUsageRemainValidSseData() {
        val frames = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"weather","arguments":"{\"city\":\"Oslo\"}"}}]}}]}""",
            """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}""",
        )
        val pretty = Json { prettyPrint = true }
        val upstream = Buffer()
        frames.forEachIndexed { index, json ->
            upstream.writeUtf8("id: $index\nevent: message\n")
            // Multi-line input is also legal SSE and must reassemble before calling the hook.
            pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(json)).lines()
                .forEach { upstream.writeUtf8("data: $it\n") }
            upstream.writeUtf8("\n")
        }
        upstream.writeUtf8("data: [DONE]\n\n")
        var calls = 0
        val source = HostPluginHttpInterceptor.EventSource(upstream) { json ->
            calls++
            val changed = JsonObject(Json.parseToJsonElement(json).jsonObject + ("hooked" to JsonPrimitive(true)))
            pretty.encodeToString(JsonElement.serializer(), changed)
        }
        val sink = Buffer()
        while (source.read(sink, 8192) != -1L) { }
        val emitted = sink.readUtf8().trim().split("\n\n")
        assertEquals(3, emitted.size)
        emitted.take(2).forEachIndexed { index, frame ->
            assertTrue(frame.lines().all { it.startsWith("data:") || it.startsWith("id:") || it.startsWith("event:") })
            val payload = frame.lines().filter { it.startsWith("data:") }
                .joinToString("\n") { it.substring(5).removePrefix(" ") }
            val expected = JsonObject(Json.parseToJsonElement(frames[index]).jsonObject + ("hooked" to JsonPrimitive(true)))
            assertEquals(expected, Json.parseToJsonElement(payload))
        }
        assertEquals("data: [DONE]", emitted.last())
        assertEquals(2, calls)
        source.close()
    }

}
