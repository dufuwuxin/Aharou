package com.aharou.feature.settings.data.account

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OurFreeModelProtocolTest {
    private class Vault : AccountCredentialVault {
        private val values = ConcurrentHashMap<String, String>()
        override fun read(id: String) = values[id]
        override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
    }

    private val kind = AccountProviderKind.OUR_FREE_MODEL
    private val catalog = """{"data":[{"id":"vendor/free","name":"Free","isFree":true},{"id":"vendor/paid","isFree":false},{"id":"pretend-free","isFree":"true"},{"id":"unknown-free"}]}"""
    private val sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\\n    \"},\"finish_reason\":null}]}\n\n" +
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"return 1\\n\"},\"finish_reason\":null}]}\n\n" +
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    private fun manager(server: MockWebServer) = AccountProviderManager(Vault(),
        OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).followRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                assertEquals("https", request.url.scheme)
                assertEquals("ai-gateway.kilo.ai", request.url.host)
                assertNull(request.header("Authorization"))
                assertNull(request.header("Cookie"))
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }.build())

    private fun gateway(manager: AccountProviderManager) = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
        .addInterceptor(AccountGatewayInterceptor(manager)).build()

    private fun completion(manager: AccountProviderManager, streaming: Boolean, model: String = "vendor/free") = Request.Builder()
        .url(manager.baseUrl(kind, "provider") + "/chat/completions")
        .post("""{"model":"$model","messages":[{"role":"user","content":"Hi"}],"stream":$streaming,"tools":[{"type":"function","function":{"name":"fixture","parameters":{"type":"object"}}}]}""".toRequestBody("application/json".toMediaType()))
        .build()

    @Test fun onlyExplicitBooleanFreeRowsAreSelectable() {
        val payload = JsonParser.parseString(catalog).asJsonObject
        assertEquals(listOf("vendor/free"), AccountProviderProtocol.models(kind, payload).map { it.id })
        assertEquals(kind, AccountProviderKind.fromWireId("our-free-model"))
    }

    @Test fun connectAndReconnectKeepOneLocalSlotWithoutCredentials() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            repeat(2) {
                server.enqueue(MockResponse().setBody(catalog))
                manager.connectFreeModels("provider")
            }
            assertTrue(manager.isLoggedIn("provider"))
            assertEquals(1, manager.accounts("provider", kind).size)
            repeat(2) { assertEquals("/api/v1/models", server.takeRequest(1, TimeUnit.SECONDS)!!.path) }
            assertTrue(manager.headers(OurFreeModelProtocol.connection()).isEmpty())
        }
    }

    @Test fun failuresAndPaidOnlyCatalogDoNotSaveConnection() = runBlocking {
        for (response in listOf(MockResponse().setBody("""{"data":[{"id":"paid","isFree":false}]}"""),
            MockResponse().setResponseCode(403).setBody("blocked"))) {
            MockWebServer().use { server ->
                val manager = manager(server)
                server.enqueue(response)
                try { manager.connectFreeModels("provider"); fail("Connection must fail") }
                catch (error: AccountProviderException) {
                    assertTrue(error.errorCode == "empty_catalog" || error.httpStatus == 403)
                }
                assertFalse(manager.isLoggedIn("provider"))
            }
        }
    }

    @Test fun gatewayForwardsToolsAndPreservesStreamWhitespaceAndAggregation() = runBlocking {
        for (streaming in listOf(true, false)) MockWebServer().use { server ->
            val manager = manager(server)
            server.enqueue(MockResponse().setBody(catalog))
            manager.connectFreeModels("provider")
            server.enqueue(MockResponse().setBody(catalog))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
            gateway(manager).newCall(completion(manager, streaming)).execute().use { response ->
                assertEquals(200, response.code)
                val result = response.body!!.string()
                if (streaming) assertEquals(sse, result)
                else assertEquals("\n    return 1\n", JsonParser.parseString(result).asJsonObject
                    .getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString)
            }
            repeat(2) { server.takeRequest(1, TimeUnit.SECONDS)!! }
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/chat/completions", request.path)
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("vendor/free", body.get("model").asString)
            assertEquals(streaming, body.get("stream").asBoolean)
            assertEquals("fixture", body.getAsJsonArray("tools")[0].asJsonObject.getAsJsonObject("function").get("name").asString)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun fragmentedToolCallsAndToolResultRoundTripPreserveArguments() = runBlocking {
        val fragments = listOf("{\"text\":\"", " ", "value", " ", "\"}")
        val events = fragments.mapIndexed { index, argument ->
            val function = com.google.gson.JsonObject().apply {
                if (index == 0) addProperty("name", "fixture")
                addProperty("arguments", argument)
            }
            val call = com.google.gson.JsonObject().apply {
                addProperty("index", 0)
                if (index == 0) { addProperty("id", "call-fixture"); addProperty("type", "function") }
                add("function", function)
            }
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[$call]},\"finish_reason\":null}]}\n\n"
        }.joinToString("") + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
        for (streaming in listOf(true, false)) MockWebServer().use { server ->
            val manager = manager(server)
            server.enqueue(MockResponse().setBody(catalog))
            manager.connectFreeModels("provider")
            server.enqueue(MockResponse().setBody(catalog))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events))
            gateway(manager).newCall(completion(manager, streaming)).execute().use { response ->
                assertEquals(200, response.code)
                val result = response.body!!.string()
                if (streaming) assertEquals(events, result)
                else {
                    val choice = JsonParser.parseString(result).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
                    assertEquals("tool_calls", choice.get("finish_reason").asString)
                    val call = choice.getAsJsonObject("message").getAsJsonArray("tool_calls")[0].asJsonObject
                    assertEquals("call-fixture", call.get("id").asString)
                    assertEquals("fixture", call.getAsJsonObject("function").get("name").asString)
                    assertEquals(fragments.joinToString(""), call.getAsJsonObject("function").get("arguments").asString)
                }
            }
            repeat(3) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            val followup = JsonParser.parseString("""{"model":"vendor/free","stream":false,"messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"call-fixture","type":"function","function":{"name":"fixture","arguments":"{\"text\":\" value \"}"}}]},{"role":"tool","tool_call_id":"call-fixture","content":"\n  result\t\n"}]}""").asJsonObject
            server.enqueue(MockResponse().setBody(catalog))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}"""))
            val request = completion(manager, false).newBuilder()
                .post(followup.toString().toRequestBody("application/json".toMediaType())).build()
            gateway(manager).newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertTrue(response.body!!.string().contains("done"))
            }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            val sent = JsonParser.parseString(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).asJsonObject
            assertEquals(followup.get("messages"), sent.get("messages"))
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun closingPartialStreamReleasesUpstreamWithoutReplay() = runBlocking {
        MockWebServer().use { server ->
            val ended = java.util.concurrent.CountDownLatch(1)
            val upstream = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                .eventListener(object : okhttp3.EventListener() {
                    override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) {
                        if (call.request().url.encodedPath.endsWith("/chat/completions")) ended.countDown()
                    }
                })
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                    .url(server.url(chain.request().url.encodedPath)).build()) }.build()
            val manager = AccountProviderManager(Vault(), upstream)
            server.enqueue(MockResponse().setBody(catalog))
            manager.connectFreeModels("provider")
            server.enqueue(MockResponse().setBody(catalog))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(sse.repeat(200)).throttleBody(128, 100, TimeUnit.MILLISECONDS))
            val response = gateway(manager).newCall(completion(manager, true)).execute()
            assertEquals(200, response.code)
            assertTrue(response.body!!.source().readUtf8LineStrict().startsWith("data: "))
            response.close()
            assertTrue("Closing the gateway stream must release the upstream body", ended.await(2, TimeUnit.SECONDS))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun paidModelsAnd400NeverTriggerAnotherCompletion() = runBlocking {
        for (model in listOf("vendor/paid", "vendor/free")) MockWebServer().use { server ->
            val manager = manager(server)
            server.enqueue(MockResponse().setBody(catalog))
            manager.connectFreeModels("provider")
            server.enqueue(MockResponse().setBody(catalog))
            if (model == "vendor/free") server.enqueue(MockResponse().setResponseCode(400).setBody("invalid parameters"))
            gateway(manager).newCall(completion(manager, true, model)).execute().use { assertEquals(400, it.code) }
            assertEquals(if (model == "vendor/free") 3 else 2, server.requestCount)
        }
    }

    @Test fun logoutWhileConnectingCannotSaveLateDirectory() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            server.enqueue(MockResponse().setBody(catalog).setBodyDelay(1, TimeUnit.SECONDS))
            val connecting = async(Dispatchers.IO) {
                try { manager.connectFreeModels("provider"); false } catch (_: java.io.IOException) { true }
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            manager.logout("provider")
            assertTrue(connecting.await())
            assertFalse(manager.isLoggedIn("provider"))
        }
    }
}
