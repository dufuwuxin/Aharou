package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
                if (request.url.encodedPath == "/chat/") assertNull(request.url.queryParameter("aid"))
                else assertEquals("497858", request.url.queryParameter("aid"))
                val target = server.url(request.url.encodedPath + "?" + request.url.encodedQuery)
                chain.proceed(request.newBuilder().url(target).build()).also { response ->
                    try { afterResponse(request.url.encodedPath) }
                    catch (error: IOException) { response.close(); throw error }
                }
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

    private fun websitePage(uid: String = "123456789", visitor: Boolean = false): String {
        val config = JsonParser.parseString(catalog).asJsonObject.obj("data")!!.array("entry_list")[0]
            .asJsonObject.obj("active_switch_conf")!!.obj("menu_conf_v2")!!
        val layout = JsonObject().apply {
            add("accountInfo", JsonParser.parseString("""{"data":{"user_id":"$uid","is_visitor_account":$visitor},"message":"success"}"""))
            add("actionBarBriefList", JsonObject().apply {
                add("request", JsonParser.parseString("""{"bot_id":"7338286299411103781","language_code":"zh"}"""))
                add("modeSelectData", JsonObject().apply { add("menuConfV2", config) })
            })
        }
        val router = JsonObject().apply { add("loaderData", JsonObject().apply {
            add("chat_layout", JsonObject().apply { add("chat_layout", layout) })
        }) }
        return "<html><script>window._ROUTER_DATA = $router;</script></html>"
    }

    @Test fun authenticatedWebsiteDirectoryRepairsMissingLegacyModelSwitchWithoutDefaults() = runBlocking {
        MockWebServer().use { server ->
            val vault = Vault()
            val manager = manager(server, vault)
            val pending = manager.beginLogin("provider", kind)
            server.enqueue(MockResponse().setBody(user))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"entry_list":[{"action_bar_key":"unrelated"}]}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(websitePage()))
            assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
            manager.awaitLogin(pending)
            assertTrue(manager.isLoggedIn("provider"))
            assertEquals("123456789", manager.credential("provider", kind).userId)
            repeat(2) { server.takeRequest(1, TimeUnit.SECONDS)!! }
            val page = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", page.method)
            assertEquals("/chat/", page.requestUrl!!.encodedPath)
            assertEquals(cookie, page.getHeader("Cookie"))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"entry_list":[]}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(websitePage()))
            assertEquals(listOf("7"), manager.snapshot("provider", kind).models.map { it.id })
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"entry_list":[]}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(websitePage()))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ack + text + end))
            call(manager, false).execute().use { response ->
                assertEquals(200, response.code)
                assertTrue(response.body!!.string().contains("你好🌏"))
            }
            assertEquals(8, server.requestCount)
        }
    }

    @Test fun websiteDirectoryRejectsOtherUserVisitorAndRedirectWithoutSavingOrSendingCookieElsewhere() = runBlocking {
        for ((pageResponse, expected) in listOf(
            MockResponse().setHeader("Content-Type", "text/html").setBody(websitePage("987654321")) to "identity",
            MockResponse().setHeader("Content-Type", "text/html").setBody(websitePage(visitor = true)) to "identity",
            MockResponse().setHeader("Content-Type", "text/html").setBody("<script>window.defaultModels = [];</script>") to "missing",
            MockResponse().setResponseCode(302).setHeader("Location", "https://untrusted.example/chat/") to "http_302",
        )) MockWebServer().use { server ->
            val vault = Vault()
            val manager = manager(server, vault)
            val pending = manager.beginLogin("provider", kind)
            server.enqueue(MockResponse().setBody(user))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"entry_list":[]}}"""))
            server.enqueue(pageResponse)
            assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
            try { manager.awaitLogin(pending); fail("Unverified website directory accepted") }
            catch (error: AccountProviderException) { assertEquals("doubao_catalog_web_$expected", error.errorCode) }
            assertTrue(vault.values.isEmpty())
            assertEquals(3, server.requestCount)
            manager.cancelLogin(pending)
        }
    }

    @Test fun websiteLoginCatalogUsesWebAppIdentityInsteadOfWorkClient() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                .callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
                    val request = chain.request()
                    val url = server.url(request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()
                    chain.proceed(request.newBuilder().url(url).build())
                }.build()
            val manager = AccountProviderManager(Vault(), client)
            login(manager, server)
            val identity = server.takeRequest(1, TimeUnit.SECONDS)!!
            val directory = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("497858", identity.requestUrl!!.queryParameter("aid"))
            assertEquals("497858", directory.requestUrl!!.queryParameter("aid"))
            assertEquals(DoubaoAccountProtocol.CATALOG, directory.requestUrl!!.encodedPath)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun emptyLoginDirectoryReportsOnlyBoundedStructuralCounts() = runBlocking {
        MockWebServer().use { server ->
            val vault = Vault()
            val manager = manager(server, vault)
            val pending = manager.beginLogin("provider", kind)
            server.enqueue(MockResponse().setBody(user))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"entry_list":[{"action_bar_key":"coco_deep_thinking","active_switch_conf":{},"private":"private-response-marker"}]}}"""))
            assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
            try { manager.awaitLogin(pending); fail("Empty directory accepted") }
            catch (error: AccountProviderException) {
                assertEquals("doubao_catalog_empty_shape_1_1_0_0_0", error.errorCode)
                assertEquals(error.errorCode, DoubaoAccountProtocol.loginFailureCode(error.errorCode))
                assertFalse(error.message!!.contains("private-response-marker"))
                assertFalse(error.message!!.contains(cookie))
            }
            assertTrue(vault.values.isEmpty())
            assertEquals(2, server.requestCount)
            assertNull(DoubaoAccountProtocol.loginFailureCode("doubao_catalog_empty_shape_1_0_0_0_private"))
            manager.cancelLogin(pending)
            val restricted = JsonParser.parseString(catalog).asJsonObject
            restricted.obj("data")!!.array("entry_list")[0].asJsonObject.obj("active_switch_conf")!!
                .obj("menu_conf_v2")!!.obj("mode_list")!!.array("item_list")[0].asJsonObject
                .obj("model_list")!!.array("item_list").remove(0)
            val oversized = JsonParser.parseString("""{"code":0,"data":{"entry_list":[{"action_bar_key":"coco_deep_thinking","active_switch_conf":{}}]}}""").asJsonObject.apply {
                repeat(999) { obj("data")!!.array("entry_list").add(JsonObject()) }
            }
            for ((payload, expected) in listOf(restricted to "1_1_2_1_3", oversized to "999_1_0_0_0")) {
                val attempt = manager.beginLogin("provider", kind)
                server.enqueue(MockResponse().setBody(user))
                server.enqueue(MockResponse().setBody(payload.toString()))
                assertTrue(manager.acceptWebLogin(attempt, attempt.url, attempt.id, attempt.state, cookie))
                try { manager.awaitLogin(attempt); fail("Empty directory accepted") }
                catch (error: AccountProviderException) {
                    assertEquals("doubao_catalog_empty_shape_$expected", error.errorCode)
                    assertEquals(error.errorCode, DoubaoAccountProtocol.loginFailureCode(error.errorCode))
                }
                assertTrue(vault.values.isEmpty())
                manager.cancelLogin(attempt)
            }
            assertEquals(6, server.requestCount)
            assertNull(DoubaoAccountProtocol.loginFailureCode("doubao_catalog_empty_shape_1000_0_0_0_0"))
        }
    }

    @Test fun workModelsKeepNativeRoutingSeparateAndNeverIncludeUpgradeOrDisabledRows() {
        val payload = JsonParser.parseString(catalog).asJsonObject
        val mode = payload.obj("data")!!.array("entry_list")[0].asJsonObject.obj("active_switch_conf")!!
            .obj("menu_conf_v2")!!.obj("mode_list")!!.array("item_list")[1].asJsonObject
        mode.addProperty("mode_id", "3")
        mode.obj("model_list")!!.array("item_list").apply {
            add(JsonParser.parseString("""{"model_item_key":"person-fixture","item_id":10001,"name":"Fixture Work","model_extra_params":{"provider_id":"fixture-provider"}}"""))
            add(JsonParser.parseString("""{"model_item_key":"paid","item_id":10001,"name":"Paid","subscribe_config":{"need_upgrade":true}}"""))
            add(JsonParser.parseString("""{"model_item_key":"disabled","item_id":10001,"name":"Disabled","disabled":true}"""))
        }
        val models = DoubaoAccountProtocol.models(payload)
        assertEquals(listOf("7", "work:10", "work:person-fixture"), models.map { it.id })
        val work = models.last()
        assertTrue(work.name.contains("Work"))
        val native = DoubaoAccountProtocol.completion(JsonParser.parseString(json(false)).asJsonObject, work)
        assertEquals("1", native.obj("option")!!.text("agent_mode"))
        assertEquals("1", native.obj("ext")!!.text("agent_mode"))
        assertEquals("3", native.obj("option")!!.obj("conversation_init_ext")!!.text("mode_id"))
        assertEquals("10001", native.obj("option")!!.text("need_deep_think"))
        assertEquals("person-fixture", native.obj("option")!!.obj("model_config")!!.text("model_item_key"))
        assertEquals("fixture-provider", native.obj("option")!!.obj("aggregate_params")!!.text("provider_id"))
        assertEquals("1", native.obj("option")!!.obj("general_task_param")!!.text("runtime_type"))
        val chat = DoubaoAccountProtocol.completion(JsonParser.parseString(json(false)).asJsonObject, models.first())
        assertEquals("2", chat.obj("option")!!.text("agent_mode"))
        assertEquals("7", chat.obj("option")!!.obj("model_config")!!.text("model_item_key"))
        mode.obj("model_list")!!.array("item_list").add(JsonParser.parseString("""{"model_item_key":"7","name":"Shared Work"}"""))
        val shared = DoubaoAccountProtocol.models(payload).filter { it.id in setOf("7", "work:7") }
        assertEquals(listOf("7", "work:7"), shared.map { it.id })
        for ((index, model) in shared.withIndex()) {
            val routed = DoubaoAccountProtocol.completion(JsonParser.parseString(json(false)).asJsonObject, model)
            assertEquals(if (index == 0) "2" else "1", routed.obj("option")!!.text("agent_mode"))
            assertEquals("7", routed.obj("option")!!.obj("model_config")!!.text("model_item_key"))
        }
    }

    @Test fun workGatewayUsesSelectedModeAndPreservesTextWithoutReplayingUnsupportedTasks() = runBlocking {
        val payload = JsonParser.parseString(catalog).asJsonObject
        payload.obj("data")!!.array("entry_list")[0].asJsonObject.obj("active_switch_conf")!!
            .obj("menu_conf_v2")!!.obj("mode_list")!!.array("item_list")[1].asJsonObject.addProperty("mode_id", "3")
        MockWebServer().use { server ->
            val manager = manager(server)
            val pending = manager.beginLogin("provider", kind)
            server.enqueue(MockResponse().setBody(user))
            server.enqueue(MockResponse().setBody(payload.toString()))
            assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
            manager.awaitLogin(pending)
            repeat(2) { server.takeRequest(1, TimeUnit.SECONDS)!! }
            for (stream in listOf(false, true)) {
                server.enqueue(MockResponse().setBody(payload.toString()))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ack + text + end))
                val body = JsonParser.parseString(json(stream)).asJsonObject.apply { addProperty("model", "work:10") }
                val client = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                    .callTimeout(5, TimeUnit.SECONDS).addInterceptor(manager.interceptor()).build()
                val request = Request.Builder().url(manager.baseUrl(kind, "provider") + "/chat/completions")
                    .post(body.toString().toRequestBody()).build()
                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    val result = response.body!!.string()
                    assertTrue(result.contains("你好🌏"))
                    if (stream) assertTrue(result.contains("[DONE]"))
                }
                server.takeRequest(1, TimeUnit.SECONDS)!!
                val sent = JsonParser.parseString(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).asJsonObject
                assertEquals("1", sent.obj("option")!!.text("agent_mode"))
                assertEquals("3", sent.obj("option")!!.obj("conversation_init_ext")!!.text("mode_id"))
                assertEquals("10", sent.obj("option")!!.obj("model_config")!!.text("model_item_key"))
                assertFalse(sent.toString().contains("work:10"))
            }
            val body = JsonParser.parseString(json(false)).asJsonObject.apply { addProperty("model", "work:10") }
            server.enqueue(MockResponse().setBody(payload.toString()))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ack + event("FETCH_STREAM", """{"fetch_type":2,"fetch_key":"private-task"}""")))
            val client = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                .callTimeout(5, TimeUnit.SECONDS).addInterceptor(manager.interceptor()).build()
            val request = Request.Builder().url(manager.baseUrl(kind, "provider") + "/chat/completions")
                .post(body.toString().toRequestBody()).build()
            client.newCall(request).execute().use { response ->
                assertEquals(502, response.code)
                val rejected = response.body!!.string()
                assertTrue(rejected.contains("unsupported_capability"))
                assertFalse(rejected.contains("private-task"))
            }
            assertEquals(8, server.requestCount)
            repeat(2) { server.takeRequest(1, TimeUnit.SECONDS)!! }
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun modeOwnedListsDoNotLeakGlobalModelsAndDisabledWorkModesStayUnavailable() {
        val payload = JsonParser.parseString(catalog).asJsonObject
        val config = payload.obj("data")!!.array("entry_list")[0].asJsonObject.obj("active_switch_conf")!!.obj("menu_conf_v2")!!
        config.addProperty("use_mode_model_list", true)
        config.add("model_list", JsonParser.parseString("""{"item_list":[{"model_item_key":"99","name":"Global only"}]}"""))
        val work = config.obj("mode_list")!!.array("item_list")[1].asJsonObject
        work.addProperty("mode_id", "3")
        assertEquals(listOf("7", "work:10"), DoubaoAccountProtocol.models(payload).map { it.id })
        work.addProperty("disabled", true)
        assertEquals(listOf("7"), DoubaoAccountProtocol.models(payload).map { it.id })
        work.remove("disabled")
        work.add("subscribe_config", JsonParser.parseString("""{"need_upgrade":true}"""))
        assertEquals(listOf("7"), DoubaoAccountProtocol.models(payload).map { it.id })
    }

    @Test fun typedDeepThinkSwitchDoesNotDependOnLegacyActionBarKey() {
        val payload = JsonParser.parseString(catalog).asJsonObject
        val entries = payload.obj("data")!!.array("entry_list")
        val chatSwitch = entries[0].asJsonObject
        chatSwitch.addProperty("action_bar_key", "current_chat_selector")
        chatSwitch.addProperty("action_type", 11)
        chatSwitch.obj("active_switch_conf")!!.addProperty("switch_type", 1)
        val unrelated = chatSwitch.deepCopy().apply {
            addProperty("action_bar_key", "network_search")
            obj("active_switch_conf")!!.addProperty("switch_type", 2)
        }
        entries.add(unrelated)
        assertEquals(listOf("7"), DoubaoAccountProtocol.models(payload).map { it.id })
        chatSwitch.addProperty("action_type", 7)
        assertTrue(DoubaoAccountProtocol.models(payload).isEmpty())
        chatSwitch.addProperty("action_type", 11)
        chatSwitch.obj("active_switch_conf")!!.addProperty("switch_type", 2)
        assertTrue(DoubaoAccountProtocol.models(payload).isEmpty())
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
                    } else outcomes.offer(error)
                })
                assertEquals("doubao_identity_http_503", kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { outcomes.poll(5, TimeUnit.SECONDS) })
                assertFalse(manager.isLoggedIn("provider"))
                assertFalse(pending.closed.get())
                server.enqueue(MockResponse().setBody(user))
                server.enqueue(MockResponse().setResponseCode(403).setBody("private-response-marker"))
                assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie) { error ->
                    outcomes.offer(error ?: "unexpected-success")
                })
                assertEquals("doubao_catalog_http_403", kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { outcomes.poll(5, TimeUnit.SECONDS) })
                assertTrue(vault.values.isEmpty())
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
            server.enqueue(MockResponse().setBody(user)); server.enqueue(MockResponse().setBody("""{"code":0,"data":{"entry_list":[{"action_bar_key":"coco_deep_thinking","active_switch_conf":{"menu_conf":{"item_list":[]}}}]}}"""))
            assertTrue(manager.acceptWebLogin(empty, empty.url, empty.id, empty.state, cookie))
            try { manager.awaitLogin(empty); fail("Empty catalog accepted") } catch (_: AccountProviderException) { }
            assertFalse(manager.isLoggedIn("provider"))
            val challenged = manager.beginLogin("provider", kind)
            server.enqueue(MockResponse().setResponseCode(403).setBody("private challenge content"))
            assertTrue(manager.acceptWebLogin(challenged, challenged.url, challenged.id, challenged.state, cookie))
            try { manager.awaitLogin(challenged); fail("Challenge accepted") } catch (error: AccountProviderException) {
                assertEquals("doubao_identity_http_403", error.errorCode)
                assertEquals(403, error.httpStatus)
                assertFalse(error.message!!.contains("private challenge content"))
            }
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun loginFailuresPreserveStageAndHttpStatusWithoutPersistingOrLeakingBodies() = runBlocking {
        MockWebServer().use { server ->
            val vault = Vault()
            val manager = manager(server, vault)
            for (stage in listOf("identity", "catalog")) {
                for (status in listOf(302, 401, 403, 503)) {
                    val pending = manager.beginLogin("provider", kind)
                    if (stage == "catalog") server.enqueue(MockResponse().setBody(user))
                    server.enqueue(MockResponse().setResponseCode(status)
                        .setHeader("Location", "https://untrusted.example/login")
                        .setBody("private-response-marker"))
                    val before = server.requestCount
                    assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
                    try {
                        manager.awaitLogin(pending)
                        fail("HTTP failure accepted")
                    } catch (error: AccountProviderException) {
                        assertEquals("doubao_${stage}_http_$status", error.errorCode)
                        assertEquals(status, error.httpStatus)
                        assertEquals(error.errorCode, DoubaoAccountProtocol.loginFailureCode(error.errorCode))
                        assertFalse(error.message.orEmpty().contains("private-response-marker"))
                        assertFalse(error.message.orEmpty().contains(cookie))
                    }
                    assertEquals(if (stage == "catalog") 2 else 1, server.requestCount - before)
                    assertTrue(vault.values.isEmpty())
                    assertFalse(manager.isLoggedIn("provider"))
                }
            }
            for ((body, expected) in listOf(
                """{"code":0,"data":{}}""" to "doubao_identity_invalid",
                """{"code":0,"data":{"user_id_str":"123456789","is_visitor_account":true}}""" to "doubao_identity_invalid",
                "not-json" to "doubao_identity_invalid",
            )) {
                val pending = manager.beginLogin("provider", kind)
                server.enqueue(MockResponse().setBody(body))
                assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
                try { manager.awaitLogin(pending); fail("Invalid identity accepted") }
                catch (error: AccountProviderException) { assertEquals(expected, error.errorCode) }
                assertTrue(vault.values.isEmpty())
            }
            val pending = manager.beginLogin("provider", kind)
            server.enqueue(MockResponse().setBody(user))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"entry_list":[{"action_bar_key":"coco_deep_thinking","active_switch_conf":{"menu_conf":{"item_list":[]}}}]}}"""))
            assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
            try { manager.awaitLogin(pending); fail("Empty directory accepted") }
            catch (error: AccountProviderException) { assertEquals("doubao_catalog_empty_shape_1_1_0_0_0", error.errorCode) }
            assertTrue(vault.values.isEmpty())
        }
    }

    @Test fun loginTransportFailureNamesTheStageWithoutExposingExceptionText() = runBlocking {
        for ((stage, path) in listOf("identity" to DoubaoAccountProtocol.ACCOUNT, "catalog" to DoubaoAccountProtocol.CATALOG)) {
            MockWebServer().use { server ->
                val vault = Vault()
                val manager = manager(server, vault) { actualPath ->
                    if (actualPath == path) throw IOException("private-network-marker")
                }
                server.enqueue(MockResponse().setBody(user))
                if (stage == "catalog") server.enqueue(MockResponse().setBody(catalog))
                val pending = manager.beginLogin("provider", kind)
                assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
                try { manager.awaitLogin(pending); fail("Transport failure accepted") }
                catch (error: AccountProviderException) {
                    assertEquals("doubao_${stage}_network", error.errorCode)
                    assertFalse(error.message.orEmpty().contains("private-network-marker"))
                }
                assertTrue(vault.values.isEmpty())
            }
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

    @Test fun legacyChatCatalogIsUsedWhenV2ExistsButHasNoUsableChatModels() {
        val payload = JsonParser.parseString("""{"code":0,"data":{"entry_list":[{"action_bar_key":"coco_deep_thinking","active_switch_conf":{"menu_conf_v2":{},"menu_conf":{"item_list":[{"item_list":[{"item_id":"12","name":"Legacy chat","model_extra_params":{"model_type":"normal"}}]}]}}}]}}""").asJsonObject
        val models = DoubaoAccountProtocol.models(payload)
        assertEquals("12", models.single().id)
        assertEquals("12", models.single().function)
    }

    @Test fun legacyFallbackStillRejectsWorkOnlyDisabledAndUpgradeModels() {
        val payload = JsonParser.parseString("""{"code":0,"data":{"entry_list":[{"action_bar_key":"coco_deep_thinking","active_switch_conf":{"menu_conf_v2":{"mode_list":{"item_list":[{"agent_mode":1,"model_list":{"item_list":[{"model_item_key":"1","name":"Work only"}]}}]}},"menu_conf":{"item_list":[{"agent_mode":"2","item_list":[{"item_id":"2","name":"Disabled","disabled":true},{"item_id":"3","name":"Upgrade","subscribe_config":{"need_upgrade":true}}]}]}}}]}}""").asJsonObject
        assertTrue(DoubaoAccountProtocol.models(payload).isEmpty())
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
