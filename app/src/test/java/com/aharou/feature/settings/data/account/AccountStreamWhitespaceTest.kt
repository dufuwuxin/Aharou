package com.aharou.feature.settings.data.account

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AccountStreamWhitespaceTest {
    private val pieces = listOf("def", " ", "f():", "\n", "    ", "return", "\t", "1", "\r\n", "\u3000")
    private val expected = pieces.joinToString("")
    private fun quoted(value: String) = JsonPrimitive(value).toString()
    private fun event(name: String, data: String) = "event: $name\ndata: $data\n\n"
    private fun body(value: String) = value.toResponseBody("text/event-stream".toMediaType())
    private fun field(value: String, streaming: Boolean, key: String): String {
        if (!streaming) return JsonParser.parseString(value).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("message").get(key).asString
        return value.lineSequence().filter { it.startsWith("data: {") }.joinToString("") {
            JsonParser.parseString(it.substring(6)).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("delta").get(key)?.asString.orEmpty()
        }
    }

    @Test fun rawContentPreservesWhitespaceWithoutWeakeningMetadataChecks() {
        val value = JsonParser.parseString("""{"text":" \n\t","id":" ","number":1,"missing":null}""").asJsonObject
        assertEquals(" \n\t", value.rawText("text"))
        assertNull(value.text("id"))
        assertNull(value.rawText("number"))
        assertNull(value.rawText("missing"))
    }

    @Test fun deepSeekFragmentArraysPreserveTextAndReasoningWhitespace() {
        val wire = pieces.joinToString("") { part ->
            "data: {\"v\":{\"response\":{\"fragments\":[{\"type\":\"THINK\",\"content\":${quoted(part)}},{\"type\":\"RESPONSE\",\"content\":${quoted(part)}}]}}}\n\n"
        } + "data: [DONE]\n\n"
        val converted = DeepSeekAccountSse.body(body(wire), "fixture-model").use { it.string() }
        assertEquals(expected, field(converted, true, "content"))
        assertEquals(expected, field(converted, true, "reasoning_content"))
    }

    @Test fun doubaoPreservesNativeAndAggregatedTextAndReasoning() {
        val wire = event("SSE_ACK", "{}") + pieces.joinToString("") { part ->
            event("STREAM_CHUNK", """{"patch_op":[{"patch_value":{"content_block":[{"block_type":10000,"content":{"text_block":{"text":${quoted(part)}}}},{"block_type":10040,"content":{"thinking_block":{"content":${quoted(part)}}}}]}}]}""")
        } + event("SSE_REPLY_END", """{"end_type":3}""")
        for (streaming in listOf(true, false)) {
            val converted = DoubaoAccountProtocol.responseBody(body(wire), "fixture-model", streaming).use { it.string() }
            assertEquals(expected, field(converted, streaming, "content"))
            assertEquals(expected, field(converted, streaming, "reasoning_content"))
        }
    }

    @Test fun traePreservesFallbackFieldsArraysAndAggregatedReasoning() {
        for (shape in listOf("text", "response", "array")) {
            val wire = pieces.joinToString("") { part ->
                val content = if (shape == "array") "\"content\":[{\"type\":\"text\",\"text\":${quoted(part)}}]"
                    else "\"$shape\":${quoted(part)}"
                event("output", "{$content,\"reasoning_content\":${quoted(part)}}")
            } + event("done", "{}")
            for (streaming in listOf(true, false)) {
                val converted = TraeAccountProtocol.responseBody(body(wire), "fixture-model", streaming).use { it.string() }
                assertEquals("shape=$shape streaming=$streaming", expected, field(converted, streaming, "content"))
                assertEquals(expected, field(converted, streaming, "reasoning_content"))
            }
        }
    }

    @Test fun traeToolArgumentsPreserveWhitespaceInsideStringValues() {
        val args = listOf("{\"code\":\"a", " ", "b\"}", "\n")
        val wire = args.joinToString("") { part ->
            event("output", """{"tool_calls":[{"index":0,"id":"fixture-call","function":{"name":"fixture_tool","arguments":${quoted(part)}}}]}""")
        } + event("done", "{}")
        val converted = TraeAccountProtocol.responseBody(body(wire), "fixture-model", false).use { it.string() }
        val call = JsonParser.parseString(converted).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("message").getAsJsonArray("tool_calls")[0].asJsonObject.getAsJsonObject("function")
        assertEquals(args.joinToString(""), call.get("arguments").asString)
    }

    @Test fun actualGatewayPreservesTextReasoningAndToolArgumentFragments() {
        val args = listOf("{\"code\":\"a", " ", "b\"}", "\n")
        val textFrames = pieces.joinToString("") { part ->
            "data: {\"choices\":[{\"delta\":{\"content\":${quoted(part)},\"reasoning_content\":${quoted(part)}}}]}\n\n"
        }
        val toolFrames = args.mapIndexed { index, part ->
            val identity = if (index == 0) "\"id\":\"fixture-call\",\"type\":\"function\"," else ""
            val name = if (index == 0) "\"name\":\"fixture_tool\"," else ""
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{${identity}\"index\":0,\"function\":{${name}\"arguments\":${quoted(part)}}}]}}]}\n\n"
        }.joinToString("")
        for (streaming in listOf(true, false)) MockWebServer().use { server ->
            val storage = ConcurrentHashMap<String, String>()
            val vault = object : AccountCredentialVault {
                override fun read(id: String) = storage[id]
                override fun write(id: String, value: String?) { if (value == null) storage.remove(id) else storage[id] = value }
            }
            val transport = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).callTimeout(5, TimeUnit.SECONDS).build()
            val manager = AccountProviderManager(vault, transport, AccountEndpoints(raccoon = server.url("/").toString().trimEnd('/')))
            manager.importCredentials("id", AccountProviderKind.RACCOON, """{"accessToken":"fixture-access"}""")
            server.enqueue(MockResponse().setBody("""{"data":{"categories":[{"type":"chat","models":[{"name":"fixture-model"}]}]}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(textFrames + toolFrames + "data: [DONE]\n\n"))
            val client = transport.newBuilder().addInterceptor(manager.interceptor()).build()
            val request = Request.Builder().url(manager.baseUrl(AccountProviderKind.RACCOON, "id") + "/chat/completions").post(
                """{"model":"fixture-model","stream":$streaming,"messages":[{"role":"user","content":"fixture"}]}""".toRequestBody()).build()
            val converted = client.newCall(request).execute().use { response ->
                val value = response.body!!.string()
                assertEquals(value, 200, response.code)
                value
            }
            assertEquals(expected, field(converted, streaming, "content"))
            assertEquals(expected, field(converted, streaming, "reasoning_content"))
            val arguments = if (streaming) converted.lineSequence().filter { it.startsWith("data: {") }.joinToString("") {
                JsonParser.parseString(it.substring(6)).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("delta").getAsJsonArray("tool_calls")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("function")?.get("arguments")?.asString.orEmpty()
            } else JsonParser.parseString(converted).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message").getAsJsonArray("tool_calls")[0].asJsonObject.getAsJsonObject("function").get("arguments").asString
            assertEquals(args.joinToString(""), arguments)
        }
    }
}
