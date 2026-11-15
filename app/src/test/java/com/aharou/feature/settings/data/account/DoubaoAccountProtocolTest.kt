package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DoubaoAccountProtocolTest {
    private val kind = AccountProviderKind.DOUBAO
    // Synthetic test input, not an upstream/user session.
    private val cookie = "sessionid=fixture-only; passport_csrf_token=fixture-csrf"
    private val user = """{"code":0,"data":{"user_id_str":"123456789","is_visitor_account":false}}"""
    private val catalog = """{"code":0,"data":{"entry_list":[{"action_bar_key":"coco_deep_thinking","active_switch_conf":{"menu_conf_v2":{"mode_list":{"item_list":[{"agent_mode":2,"model_list":{"item_list":[{"model_item_key":"7","name":"Fixture chat"},{"model_item_key":"8","name":"Disabled","disabled":true},{"model_item_key":"9","name":"Upgrade","subscribe_config":{"need_upgrade":true}}]}},{"agent_mode":1,"model_list":{"item_list":[{"model_item_key":"10","name":"Work only"}]}}]}}}}]}}"""
    private fun event(name: String, data: String) = "event: $name\ndata: $data\n\n"
    private val ack = event("SSE_ACK", """{"ack_client_meta":{"conversation_id":"fixture-conversation"}}""")
    private val text = event("STREAM_CHUNK", """{"patch_op":[{"patch_value":{"content_block":[{"block_type":10040,"content":{"thinking_block":{"content":"思考"}}},{"block_type":10000,"content":{"text_block":{"text":"你好🌏"}}}]}}]}""")
    private val end = event("SSE_REPLY_END", """{"end_type":3}""")
    private class Vault : AccountCredentialVault {
        val values = ConcurrentHashMap<String, String>()
        override fun read(id: String) = values[id]
        override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
    }
    private fun manager(server: MockWebServer, vault: Vault = Vault(), afterResponse: (String) -> Unit = {}): AccountProviderManager = AccountProviderManager(vault,
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).readTimeout(3, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                assertEquals("https", request.url.scheme)
                assertEquals("www.doubao.com", request.url.host)
                assertEquals(cookie, request.header("Cookie"))
                assertNull(request.header("Authorization"))
                assertEquals("fixture-csrf", request.header("x-tt-passport-csrf-token"))
                assertEquals(if (request.url.encodedPath.startsWith("/passport/")) "497858" else "1044603", request.url.queryParameter("aid"))
                val target = server.url(request.url.encodedPath + "?" + request.url.encodedQuery)
                chain.proceed(request.newBuilder().url(target).build()).also { afterResponse(request.url.encodedPath) }
            }.build())
    private suspend fun login(manager: AccountProviderManager, server: MockWebServer) {
        server.enqueue(MockResponse().setBody(user)); server.enqueue(MockResponse().setBody(catalog))
        val pending = manager.beginLogin("provider", kind)
        assertEquals(DoubaoAccountProtocol.LOGIN, pending.url)
        assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
        assertFalse(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
        manager.awaitLogin(pending)
    }
    private fun json(stream: Boolean) = """{"model":"7","messages":[{"role":"system","content":"Reply in Chinese"},{"role":"user","content":"Earlier"},{"role":"assistant","content":"Earlier reply"},{"role":"user","content":"Hello"}],"stream":$stream}"""
    private fun call(manager: AccountProviderManager, stream: Boolean) = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).addInterceptor(manager.interceptor()).build().newCall(
        Request.Builder().url(manager.baseUrl(kind, "provider") + "/chat/completions").post(json(stream).toRequestBody()).build(),
    )
    private fun failure(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: AccountProviderException) { assertEquals(code, error.errorCode) }
    }

    @Test fun cookieIdentityCatalogPoolRestoreAndUnsupportedBalance() = runBlocking {
        MockWebServer().use { server ->
            val vault = Vault(); val manager = manager(server, vault)
            login(manager, server)
            val identity = server.takeRequest(); val directory = server.takeRequest()
            assertEquals(DoubaoAccountProtocol.ACCOUNT, identity.requestUrl!!.encodedPath)
            assertEquals(DoubaoAccountProtocol.CATALOG, directory.requestUrl!!.encodedPath)
            assertEquals("POST", directory.method)
            assertEquals("zh", JsonParser.parseString(directory.body.readUtf8()).asJsonObject.text("language_code"))
            assertEquals("123456789", manager.credential("provider", kind).userId)
            val restored = manager(server, vault).credential("provider", kind)
            assertEquals(cookie, restored.webCookie)
            assertEquals(identity.requestUrl!!.queryParameter("device_id"), restored.deviceId)
            assertFalse(restored.toString().contains(cookie))
            assertEquals(listOf(1), manager.accounts("provider", kind).map { it.position })
            server.enqueue(MockResponse().setBody(catalog))
            val snapshot = manager.snapshot("provider", kind)
            assertEquals(listOf("7"), snapshot.models.map { it.id })
            assertNull(snapshot.balance)
            assertEquals("balance_unsupported", snapshot.balanceError)
            assertEquals(AccountCheckInStatus.UNSUPPORTED, manager.checkIn("provider", kind).status)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun httpNativeJsonAndSegmentedUtf8SsePreserveTextThinkingWithoutFabricatedUsage() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server); login(manager, server)
            server.takeRequest(); server.takeRequest()
            for (stream in listOf(false, true)) {
                server.enqueue(MockResponse().setBody(catalog))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody(Buffer().writeUtf8(ack + text + end)).setChunkedBody(ack + text + end, 1))
                call(manager, stream).execute().use { response ->
                    assertEquals(200, response.code)
                    val body = response.body!!.string()
                    assertTrue(body.contains("你好🌏")); assertTrue(body.contains("思考")); assertFalse(body.contains("usage"))
                    if (stream) assertTrue(body.contains("[DONE]")) else {
                        assertEquals("你好🌏", JsonParser.parseString(body).asJsonObject.array("choices").first().asJsonObject.obj("message")!!.text("content"))
                    }
                }
                server.takeRequest()
                val chat = server.takeRequest()
                assertEquals(DoubaoAccountProtocol.CHAT, chat.requestUrl!!.encodedPath)
                assertEquals("2.32.10", chat.requestUrl!!.queryParameter("pc_version"))
                val native = JsonParser.parseString(chat.body.readUtf8()).asJsonObject
                assertEquals("2", native.obj("option")!!.text("agent_mode"))
                assertEquals("7", native.obj("option")!!.obj("model_config")!!.text("model_item_key"))
                assertEquals("1", native.obj("option")!!.obj("general_task_param")!!.text("runtime_type"))
                val transcript = native.array("messages").first().asJsonObject.array("content_block").first().asJsonObject.obj("content")!!.obj("text_block")!!.text("text")!!
                assertTrue(transcript.indexOf("Reply in Chinese") < transcript.indexOf("Earlier"))
                assertTrue(transcript.contains("not native session history"))
                assertTrue(transcript.contains("Earlier reply"))
                assertFalse(native.has("tools")); assertFalse(native.has("usage"))
            }
        }
    }

    @Test fun truncatedErrorAndNonfinalEndNeverBecomeSuccess() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server); login(manager, server)
            for (stream in listOf(false, true)) for (tail in listOf("", event("SSE_REPLY_END", """{"end_type":1}"""), event("STREAM_ERROR", """{"error_code":"bad","error_msg":"private upstream details"}"""))) {
                server.enqueue(MockResponse().setBody(catalog))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ack + text + tail))
                call(manager, stream).execute().use { response ->
                    if (stream) {
                        try { response.body!!.string(); fail("Truncated or failed stream must throw") } catch (_: IOException) { }
                    } else { assertEquals(502, response.code); assertFalse(response.body!!.string().contains("private upstream details")) }
                }
            }
        }
    }

    @Test fun validationThatCrossesLoginExpiryNeverPersistsOrAcknowledgesSuccess() = runBlocking {
        MockWebServer().use { server ->
            val vault = Vault()
            lateinit var attempt: AccountLogin
            val manager = manager(server, vault) { path ->
                if (path == DoubaoAccountProtocol.CATALOG) {
                    AccountLogin::class.java.getDeclaredField("expiresAt").apply { isAccessible = true }.setLong(attempt, 0L)
                }
            }
            attempt = manager.beginLogin("provider", kind)
            var acknowledged = false
            server.enqueue(MockResponse().setBody(user)); server.enqueue(MockResponse().setBody(catalog))
            assertTrue(manager.acceptWebLogin(attempt, attempt.url, attempt.id, attempt.state, cookie) { error ->
                if (error == null) acknowledged = true
            })
            try { manager.awaitLogin(attempt); fail("Expired validation must not commit") }
            catch (failure: AccountProviderException) { assertEquals("login_expired", failure.errorCode) }
            assertFalse(acknowledged)
            assertTrue(vault.values.isEmpty())
        }
    }

    @Test fun validationFailureKeepsAttemptRetryableAndSuccessIsNotifiedOnlyAfterVaultWrite() = runBlocking {
        MockWebServer().use { server ->
            val vault = Vault()
            val manager = manager(server, vault)
            val pending = manager.beginLogin("provider", kind)
            val outcomes = java.util.concurrent.LinkedBlockingQueue<String>()
            val waiting = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                manager.awaitLogin(pending)
            }
            try {
                server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
                assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie) { error ->
                    if (error == null) {
                        assertTrue(vault.values.isNotEmpty())
                        outcomes.offer("saved")
                    } else outcomes.offer("retry")
                })
                assertEquals("retry", kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { outcomes.poll(5, TimeUnit.SECONDS) })
                assertFalse(manager.isLoggedIn("provider"))
                assertFalse(pending.closed.get())
                server.enqueue(MockResponse().setBody(user)); server.enqueue(MockResponse().setBody(catalog))
                assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie) { error ->
                    assertNull(error)
                    assertTrue(vault.values.isNotEmpty())
                    outcomes.offer("saved")
                })
                kotlinx.coroutines.withTimeout(5_000) { waiting.await() }
                assertEquals("saved", outcomes.poll())
                assertTrue(pending.saved.get())
                assertTrue(manager.isLoggedIn("provider"))
            } finally { waiting.cancel(); manager.cancelLogin(pending) }
        }
    }

    @Test fun realConversationUrlWithQueryAndDefaultHttpsPortCompletesThroughHttpValidation() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            val pending = manager.beginLogin("provider", kind)
            server.enqueue(MockResponse().setBody(user)); server.enqueue(MockResponse().setBody(catalog))
            assertTrue(manager.acceptWebLogin(pending, "https://www.doubao.com:443/chat/fixture-conversation?from=login#chat", pending.id, pending.state, cookie))
            manager.awaitLogin(pending)
            assertEquals("123456789", manager.credential("provider", kind).userId)
            assertEquals(DoubaoAccountProtocol.ACCOUNT, server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(DoubaoAccountProtocol.CATALOG, server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test fun wrongOriginKindStateRevisionExpiryAndReplayCannotStoreSession() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            val pending = manager.beginLogin("provider", kind)
            for (origin in listOf("https://www.dumate.cn/", "https://www.doubao.com.evil/chat/", "https://user@www.doubao.com/chat/", "https://www.doubao.com:444/chat/", "http://www.doubao.com/chat/", "https://www.doubao.com@evil.example/chat/")) {
                assertFalse(manager.acceptWebLogin(pending, origin, pending.id, pending.state, cookie))
            }
            assertFalse(manager.acceptWebLogin(pending, pending.url, pending.id, "wrong-state", cookie))
            assertFalse(manager.acceptWebLogin(pending, pending.url, "wrong-id", pending.state, cookie))
            assertFalse(pending.acceptWebCookie(pending.url, pending.id, pending.state, cookie, pending.expiresAt))
            manager.cancelLogin(pending)
            assertFalse(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
            assertFalse(manager.isLoggedIn("provider"))
            val dumate = manager.beginLogin("provider", AccountProviderKind.DUMATE)
            assertFalse(manager.acceptWebLogin(dumate, DoubaoAccountProtocol.LOGIN, dumate.id, dumate.state, cookie))
            manager.cancelLogin(dumate)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun identityVisitorEmptyCatalogAndChallengeRejectWithoutAppendOrRetry() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            for (bad in listOf("""{"code":0,"data":{}}""", """{"code":0,"data":{"user_id_str":"123456789","is_visitor_account":true}}""")) {
                val pending = manager.beginLogin("provider", kind)
                server.enqueue(MockResponse().setBody(bad))
                assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
                try { manager.awaitLogin(pending); fail("Bad identity accepted") } catch (_: AccountProviderException) { }
                assertFalse(manager.isLoggedIn("provider"))
            }
            val empty = manager.beginLogin("provider", kind)
            server.enqueue(MockResponse().setBody(user)); server.enqueue(MockResponse().setBody("""{"code":0,"data":{"entry_list":[]}}"""))
            assertTrue(manager.acceptWebLogin(empty, empty.url, empty.id, empty.state, cookie))
            try { manager.awaitLogin(empty); fail("Empty catalog accepted") } catch (_: AccountProviderException) { }
            assertFalse(manager.isLoggedIn("provider"))
            val challenged = manager.beginLogin("provider", kind)
            server.enqueue(MockResponse().setResponseCode(403).setBody("private challenge content"))
            assertTrue(manager.acceptWebLogin(challenged, challenged.url, challenged.id, challenged.state, cookie))
            try { manager.awaitLogin(challenged); fail("Challenge accepted") } catch (error: AccountProviderException) {
                assertEquals("login_required", error.errorCode); assertFalse(error.message!!.contains("private challenge content"))
            }
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun cancellationAndLogoutDiscardLateLoginResults() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            val pending = manager.beginLogin("provider", kind)
            server.enqueue(MockResponse().setBody(user).setBodyDelay(1, TimeUnit.SECONDS))
            assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { manager.awaitLogin(pending) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            manager.cancelLogin(pending); manager.logout("provider")
            awaiting.cancel()
            try { awaiting.await(); fail("Cancelled login succeeded") } catch (_: kotlinx.coroutines.CancellationException) { }
            assertFalse(manager.isLoggedIn("provider"))
            assertFalse(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun parentCallCancellationAbortsNativeStreamAndNeverEmitsDone() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server); login(manager, server)
            server.enqueue(MockResponse().setBody(catalog))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(ack + text + end).setBodyDelay(1, TimeUnit.SECONDS))
            val active = call(manager, true)
            active.execute().use { response ->
                if (response.code != 200) {
                    val paths = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS)?.path }.toList()
                    fail("Native stream was not opened: ${response.body?.string()}; requests=$paths")
                }
                active.cancel()
                try { response.body!!.string(); fail("Cancelled native stream completed") } catch (_: IOException) { }
            }
        }
    }

    @Test fun redirectsAndArbitraryRoutesNeverForwardWebsiteCookie() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server); login(manager, server)
            val credential = manager.credential("provider", kind)
            for (path in listOf("https://evil.invalid/chat/completion", "/passport/other", "/chat/completion?override=1")) {
                failure("invalid_gateway") { DoubaoAccountProtocol.request(manager, credential, path) }
            }
            val count = server.requestCount
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.invalid/"))
            failure("redirect_rejected") { DoubaoAccountProtocol.catalog(manager, credential) }
            assertEquals(count + 1, server.requestCount)
        }
    }

    @Test fun actualAssistantAdapterUsesWebsiteTextModeForBothRequestPaths() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            login(manager, server)
            val client = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).addInterceptor(manager.interceptor()).build()
            val api = retrofit2.Retrofit.Builder().baseUrl("https://api.openai.com/").client(client)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create()).build()
                .create(com.aharou.feature.agent.data.remote.openai.OpenAIApi::class.java)
            val adapter = com.aharou.feature.agent.domain.provider.OpenAIAdapter(api).apply {
                baseUrl = manager.baseUrl(kind, "provider"); model = "7"; providerId = "provider"; temperature = 0.5f
                customHeaders = mapOf("OpenAI-Service-Tier" to "ultrafast"); chatCacheKeyEnabled = true; logSessionId = "fixture-session"
            }
            val provider = WebsiteTextProvider.wrap(adapter)
            for (bad in listOf("https://accounts.aharou.invalid/doubao/x/other", "https://accounts.aharou.invalid/doubao//v1", "https://accounts.aharou.invalid/doubao/x/v1/extra", "https://accounts.aharou.invalid/dumate/x/v1")) {
                val other = io.mockk.mockk<com.aharou.feature.agent.domain.provider.AIProvider>()
                io.mockk.every { other.baseUrl } returns bad
                assertSame(other, WebsiteTextProvider.wrap(other))
            }
            val history = listOf(com.aharou.feature.agent.domain.model.AgentMessage.AssistantMessage(content = "Earlier reply", reasoning = "Earlier reasoning"),
                com.aharou.feature.agent.domain.model.AgentMessage.UserMessage(content = "Hello"))
            val tools = listOf(io.mockk.mockk<com.aharou.feature.agent.domain.tool.AgentTool>())
            repeat(2) { server.enqueue(MockResponse().setBody(catalog)); server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ack + text + end)) }
            val response = provider.complete("Reply in Chinese", history, tools, "high")
            assertTrue(response.content.isNotBlank())
            val streamed = mutableListOf<com.aharou.feature.agent.domain.provider.AIStreamChunk>()
            provider.completeStream("Reply in Chinese", history, tools, "high").collect { streamed += it }
            assertTrue(streamed.any { it is com.aharou.feature.agent.domain.provider.AIStreamChunk.Final })
            val recorded = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
            val completions = recorded.filter { it.requestUrl!!.encodedPath == DoubaoAccountProtocol.CHAT }
            assertEquals(2, completions.size)
            completions.forEach { call ->
                val body = JsonParser.parseString(call.body.readUtf8()).asJsonObject
                assertFalse(body.has("tools"))
                val transcript = body.array("messages").first().asJsonObject.array("content_block").first().asJsonObject.obj("content")!!.obj("text_block")!!.text("text")!!
                assertTrue(transcript.contains("Earlier reasoning"))
            }
        }
    }

    @Test fun catalogChatEntitlementsAndUnsupportedStructuredCapabilitiesFailClosed() {
        val models = DoubaoAccountProtocol.models(JsonParser.parseString(catalog).asJsonObject)
        assertEquals(listOf("7"), models.map { it.id })
        val source = JsonParser.parseString(json(false)).asJsonObject
        for ((key, value) in listOf("tools" to "[]", "tool_choice" to "\"auto\"", "temperature" to "0.2", "max_tokens" to "10")) {
            failure("unsupported_capability") { DoubaoAccountProtocol.completion(source.deepCopy().apply { add(key, JsonParser.parseString(value)) }, models.single()) }
        }
        for (messages in listOf("""[{"role":"tool","content":"result"}]""", """[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://example.invalid/image"}}]}]""", """[{"role":"assistant","content":"","tool_calls":[]},{"role":"user","content":"continue"}]""")) {
            failure("unsupported_history") { DoubaoAccountProtocol.completion(source.deepCopy().apply { add("messages", JsonParser.parseString(messages)) }, models.single()) }
        }
        failure("account_mismatch") { DoubaoAccountProtocol.restore(JsonObject().apply { addProperty("kind", "dumate") }) }
    }
}
