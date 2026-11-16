package com.aharou.feature.settings.data.account

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CodeArtsAccountProtocolTest {
    private class Vault : AccountCredentialVault {
        private val values = ConcurrentHashMap<String, String>()
        override fun read(id: String) = values[id]
        override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
    }
    private val kind = AccountProviderKind.CODEARTS
    private val cookie = "devclouddevuibjtcftk=fixture-session-only"
    private val identity = """{"userId":"fixture-user","domainId":"fixture-domain"}"""
    private val models = """{"data":{"gpts":{"models":[{"model_name":"Fixture model","model_parameters":{"model_id":"fixture-model"}},{"model_name":"Disabled","model_parameters":{"model_id":"disabled","enable":false}}]}}}"""
    private val sse = "event: message\ndata: {\"content\":\"Hello\",\"part_id\":\"fixture-part\"}\n\n" +
        "event: message\ndata: {\"content\":\" \",\"part_id\":\"fixture-part\"}\n\n" +
        "event: message\ndata: {\"content\":\"world\",\"part_id\":\"fixture-part\"}\n\n" +
        "event: message\ndata: {\"content\":\"\\n    \",\"part_id\":\"fixture-part\"}\n\n" +
        "event: message\ndata: {\"content\":\"return 1\",\"part_id\":\"fixture-part\"}\n\n" +
        "event: message\ndata: {\"content\":\"\\n\",\"part_id\":\"fixture-part\"}\n\n" +
        "event: done\ndata: {\"message_id\":\"fixture-message\"}\n\n"

    private fun manager(server: MockWebServer, currentCookie: () -> String = { cookie }): AccountProviderManager = AccountProviderManager(Vault(),
        OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).followRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                assertEquals("https", request.url.scheme)
                assertEquals("devcloud.cn-north-4.huaweicloud.com", request.url.host)
                assertEquals(currentCookie(), request.header("Cookie"))
                assertEquals(currentCookie().substringAfter("cftk="), request.header("cftk"))
                if (request.url.encodedPath == CodeArtsAccountProtocol.SESSION_CREATE && request.header("Agent-Type") != "CodeBase") {
                    return@addInterceptor okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(400).message("Missing CodeBase routing header")
                        .body("""{"error_code":"CODEBASE.00010001.400"}""".toResponseBody("application/json".toMediaType())).build()
                }
                val target = server.url(request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()
                chain.proceed(request.newBuilder().url(target).build()).newBuilder().request(request).build()
            }.build())

    private suspend fun login(manager: AccountProviderManager, server: MockWebServer, sessionCookie: String = cookie) {
        server.enqueue(MockResponse().setBody(identity))
        server.enqueue(MockResponse().setBody(models))
        val pending = manager.beginLogin("provider", kind)
        assertEquals(CodeArtsAccountProtocol.LOGIN, pending.url)
        assertFalse(manager.acceptWebLogin(pending, "https://devcloud.cn-north-4.huaweicloud.com.evil.invalid/", pending.id, pending.state, sessionCookie))
        assertFalse(manager.acceptWebLogin(pending, pending.url, pending.id, "wrong-state", sessionCookie))
        assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, sessionCookie))
        assertFalse(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, sessionCookie))
        manager.awaitLogin(pending)
    }

    @Test fun loginValidatesRemoteIdentityAndModelCatalogBeforeSaving() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            login(manager, server)
            val saved = JsonParser.parseString(manager.exportCredentials("provider")).asJsonObject
            assertEquals("fixture-user", saved.get("userId").asString)
            assertEquals(CodeArtsAccountProtocol.WEB, saved.get("domain").asString)
            assertEquals(cookie, saved.get("webCookie").asString)
            assertEquals(CodeArtsAccountProtocol.IDENTITY, server.takeRequest(1, TimeUnit.SECONDS)!!.path)
            val catalog = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(CodeArtsAccountProtocol.MODELS, catalog.path)
            assertEquals("AgentCenter", catalog.getHeader("Agent-Type"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun rotatingCookieKeepsStableRemoteAccountIdentity() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            login(manager, server)
            login(manager, server, "devclouddevuibjtcftk=fixture-rotated")
            assertEquals(1, manager.accounts("provider", kind).size)
        }
    }

    @Test fun missingRemoteIdentityEmptyCatalogAnd403DoNotSave() = runBlocking {
        for (failure in listOf("identity", "catalog", "403")) MockWebServer().use { server ->
            val manager = manager(server)
            when (failure) {
                "identity" -> server.enqueue(MockResponse().setBody("{}"))
                "403" -> server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
                else -> {
                    server.enqueue(MockResponse().setBody(identity))
                    server.enqueue(MockResponse().setBody("""{"data":{"gpts":{"models":[]}}}"""))
                }
            }
            val pending = manager.beginLogin("provider", kind)
            assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state, cookie))
            try { manager.awaitLogin(pending); fail("Invalid login must not save") } catch (_: IOException) { }
            assertFalse(manager.isLoggedIn("provider"))
        }
    }

    @Test fun rotatedSessionCookiesArePersistedAndUsedOnTheNextRequest() = runBlocking {
        MockWebServer().use { server ->
            var currentCookie = cookie
            val vault = Vault()
            val manager = AccountProviderManager(vault,
                OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).followRedirects(false)
                    .addInterceptor { chain ->
                        val request = chain.request()
                        assertEquals(currentCookie, request.header("Cookie"))
                        assertEquals("https", request.url.scheme)
                        assertEquals("devcloud.cn-north-4.huaweicloud.com", request.url.host)
                        assertEquals(currentCookie.substringAfter("cftk="), request.header("cftk"))
                        val target = request.newBuilder().url(server.url(request.url.encodedPath)).build()
                        chain.proceed(target).newBuilder().request(request).build()
                    }.build())
            login(manager, server)
            server.takeRequest(); server.takeRequest()
            server.enqueue(MockResponse().setBody(models).addHeader("Set-Cookie",
                "devclouddevuibjtcftk=fixture-rotated; Path=/; Secure; HttpOnly"))
            manager.snapshot("provider", kind)
            assertTrue(manager.credential("provider", kind).webCookie!!.contains("fixture-rotated"))
            currentCookie = "devclouddevuibjtcftk=fixture-rotated"
            server.takeRequest()
            server.enqueue(MockResponse().setBody(models))
            manager.snapshot("provider", kind)
            assertEquals(currentCookie, manager.credential("provider", kind).webCookie)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun completionUsesCookieRotatedBySessionCreationForMessageRequest() = runBlocking {
        MockWebServer().use { server ->
            lateinit var manager: AccountProviderManager
            manager = manager(server) {
                if (manager.isLoggedIn("provider")) manager.credential("provider", kind).webCookie.orEmpty() else cookie
            }
            login(manager, server)
            server.takeRequest(); server.takeRequest()
            server.enqueue(MockResponse().setBody(models))
            server.enqueue(MockResponse().setBody("""{"result":{"session_id":"fixture-session"}}""").addHeader("Set-Cookie",
                "devclouddevuibjtcftk=fixture-rotated; Path=/; Secure; HttpOnly"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse).addHeader("Set-Cookie",
                "devclouddevuibjtcftk=fixture-latest; Path=/; Secure; HttpOnly"))
            val client = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                .addInterceptor(manager.interceptor()).build()
            val request = Request.Builder().url(manager.baseUrl(kind, "provider") + "/chat/completions")
                .post("""{"model":"fixture-model","stream":true,"messages":[{"role":"user","content":"Hello"}]}""".toRequestBody()).build()
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            server.takeRequest()
            val create = server.takeRequest()
            val send = server.takeRequest()
            assertEquals(cookie, create.getHeader("Cookie"))
            assertEquals("devclouddevuibjtcftk=fixture-rotated", send.getHeader("Cookie"))
            assertEquals("devclouddevuibjtcftk=fixture-latest", manager.credential("provider", kind).webCookie)
        }
    }

    @Test fun catalogDispatchUsesCodeArtsModelParserAndExcludesDisabled() {
        assertEquals(listOf("fixture-model"), AccountProviderProtocol.models(kind, JsonParser.parseString(models).asJsonObject).map { it.id })
    }

    @Test fun actualGatewayUsesCloudRoutesAndBothResponseModes() = runBlocking {
        for (streaming in listOf(true, false)) MockWebServer().use { server ->
            val manager = manager(server)
            login(manager, server)
            server.takeRequest(1, TimeUnit.SECONDS); server.takeRequest(1, TimeUnit.SECONDS)
            server.enqueue(MockResponse().setBody(models))
            server.enqueue(MockResponse().setBody("""{"result":{"session_id":"fixture-session"}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
            val client = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).addInterceptor(manager.interceptor()).build()
            val request = Request.Builder().url(manager.baseUrl(kind, "provider") + "/chat/completions").post(
                """{"model":"fixture-model","stream":$streaming,"messages":[{"role":"user","content":"Hello"}]}""".toRequestBody()).build()
            client.newCall(request).execute().use { response ->
                val body = response.body!!.string()
                assertEquals(body, 200, response.code)
                val expected = "Hello world\n    return 1\n"
                if (streaming) {
                    assertTrue(body.contains("[DONE]"))
                    val text = body.lineSequence().filter { it.startsWith("data: {") }.joinToString("") { line ->
                        JsonParser.parseString(line.substring(6)).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
                            .getAsJsonObject("delta").get("content")?.asString.orEmpty()
                    }
                    assertEquals(expected, text)
                } else assertEquals(expected, JsonParser.parseString(body).asJsonObject.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString)
            }
            assertEquals(CodeArtsAccountProtocol.MODELS, server.takeRequest(1, TimeUnit.SECONDS)!!.path)
            val create = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(CodeArtsAccountProtocol.SESSION_CREATE, create.path)
            assertEquals("CodeBase", create.getHeader("Agent-Type"))
            assertEquals("{}", create.body.readUtf8())
            val prompt = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/chat/codebaseservice/v1/cloudagent/sessions/fixture-session/messages", prompt.path)
            val body = JsonParser.parseString(prompt.body.readUtf8()).asJsonObject
            assertEquals("user: Hello", body.get("content").asString)
            assertEquals("fixture-model", body.get("model_id").asString)
            assertTrue(body.getAsJsonArray("repos").isEmpty)
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun gatewayNeverReplaysCreateOrSend400And403AcrossAccounts() = runBlocking {
        for (status in listOf(400, 403)) for (stage in listOf("create_session", "send_message")) {
            MockWebServer().use { server ->
                val manager = manager(server)
                login(manager, server)
                server.enqueue(MockResponse().setBody("""{"userId":"second-user","domainId":"fixture-domain"}"""))
                server.enqueue(MockResponse().setBody(models))
                val pending = manager.beginLogin("provider", kind)
                assertTrue(manager.acceptWebLogin(pending, pending.url, pending.id, pending.state,
                    "devclouddevuibjtcftk=second-session"))
                manager.awaitLogin(pending)
                assertEquals(2, manager.accounts("provider", kind).size)
                repeat(4) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
                server.enqueue(MockResponse().setBody(models))
                if (stage == "send_message") server.enqueue(MockResponse().setBody("""{"result":{"session_id":"fixture-session"}}"""))
                server.enqueue(MockResponse().setResponseCode(status).setBody(
                    """{"error_code":"CODEBASE.FIXTURE","error_msg":"private-fixture-cookie"}"""))
                server.enqueue(MockResponse().setBody(models))
                val client = OkHttpClient.Builder().addInterceptor(manager.interceptor()).build()
                val request = Request.Builder().url(manager.baseUrl(kind, "provider") + "/chat/completions")
                    .post("""{"model":"fixture-model","stream":true,"messages":[{"role":"user","content":"Hello"}]}""".toRequestBody()).build()
                client.newCall(request).execute().use { response ->
                    assertEquals(status, response.code)
                    val body = response.body!!.string()
                    assertTrue(body.contains("CodeArts $stage returned HTTP $status"))
                    assertFalse(body.contains("private-fixture-cookie"))
                }
                val expected = if (stage == "send_message") 3 else 2
                repeat(expected) { assertEquals(cookie, server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Cookie")) }
                assertEquals(4 + expected, server.requestCount)
                assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            }
        }
    }

    @Test fun httpErrorsIdentifyStageAndSafeCodeWithoutLeakingUpstreamText() {
        for ((path, stage) in listOf(
            CodeArtsAccountProtocol.IDENTITY to "identity",
            CodeArtsAccountProtocol.MODELS to "catalog",
            CodeArtsAccountProtocol.SESSION_CREATE to "create_session",
            "/chat/codebaseservice/v1/cloudagent/sessions/fixture-session/messages" to "send_message",
        )) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error_code":"CODEBASE.00010001.400","error_msg":"private-fixture-cookie"}"""))
            val credential = AccountCredential(kind, "website-session", null, null, "device", "user", CodeArtsAccountProtocol.WEB, webCookie = cookie)
            try {
                CodeArtsAccountProtocol.request(manager(server), credential, path).close()
                fail("Upstream 400 must remain a failure")
            } catch (error: AccountProviderException) {
                assertEquals(400, error.httpStatus)
                assertEquals("CodeArts $stage returned HTTP 400 (CODEBASE.00010001.400)", error.message)
                assertFalse(error.message!!.contains("private-fixture-cookie"))
            }
        }
    }

    @Test fun errorTruncationAndRequiredApprovalNeverBecomeSuccessfulCompletion() {
        for (value in listOf(
            "event: error\ndata: {\"message\":\"fixture-error\"}\n\n",
            "event: message\ndata: {\"content\":\"partial\"}\n\n",
            "event: tool_authorization\ndata: {\"permission_id\":\"fixture\"}\n\n",
        )) {
            val raw = value.toResponseBody("text/event-stream".toMediaType())
            try { CodeArtsAccountProtocol.sseBody(raw, "fixture-model").use { it.string() }; fail("Failure must propagate") }
            catch (_: IOException) { }
        }
    }
}
