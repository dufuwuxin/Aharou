package com.aharou.feature.settings.data.account

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
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DumateAccountProtocolTest {
    private class Vault : AccountCredentialVault {
        val values = ConcurrentHashMap<String, String>()
        override fun read(id: String) = values[id]
        override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
    }
    private val kind = AccountProviderKind.DUMATE
    private val cookie = "normalcookie=fixture-user-session"
    private val catalog = """{"object":"list","data":[{"id":"fixture-directory-model"}]}"""
    private fun ticket(token: String = "fixture-short-ticket") = """{"code":0,"result":{"token":"$token","expired_at":"${Instant.now().plusSeconds(3600)}"}}"""
    private val user = """{"code":0,"result":{"bceUserId":"fixture-stable-user","displayName":"fixture display name"}}"""

    // Rewrite only in the test client; production URLs and auth scoping remain fixed.
    private fun newManager(web: MockWebServer, gateway: MockWebServer, vault: Vault = Vault()): AccountProviderManager {
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
            .addInterceptor { chain ->
                val request = chain.request()
                val server = when (request.url.host) {
                    "dumate.baidu.com" -> {
                        assertNull(request.header("Authorization"))
                        assertEquals(cookie, request.header("Cookie"))
                        web
                    }
                    "dumate-svc.baidu.com" -> {
                        assertNull(request.header("Cookie"))
                        assertTrue(request.header("Authorization")!!.startsWith("Bearer fixture-"))
                        gateway
                    }
                    else -> throw AssertionError("Unexpected upstream host")
                }
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }.build()
        return AccountProviderManager(vault, client)
    }
    private fun enqueueLogin(web: MockWebServer, gateway: MockWebServer, directory: String = catalog) {
        web.enqueue(MockResponse().setBody(ticket()))
        web.enqueue(MockResponse().setBody(user))
        gateway.enqueue(MockResponse().setBody(directory))
    }
    private suspend fun login(manager: AccountProviderManager) {
        val attempt = manager.beginLogin("provider", kind)
        assertEquals("https://dumate.baidu.com/app", attempt.url)
        assertNull(attempt.callbackPrefix)
        assertTrue(manager.acceptWebLogin(attempt, DumateAccountProtocol.WEB, attempt.id, attempt.state, cookie))
        assertFalse(manager.acceptWebLogin(attempt, DumateAccountProtocol.WEB, attempt.id, attempt.state, cookie))
        manager.awaitLogin(attempt)
    }

    @Test fun oldOriginCredentialsStayInThePoolButCannotSendCookiesToTheNewDomain() = runBlocking {
        val old = JsonParser.parseString("""{"kind":"dumate","webCookie":"BDUSS=fixture-only","userId":"fixture-user","accessToken":"fixture-ticket","expiresAt":9999999999999}""").asJsonObject
        for (origin in listOf<String?>(null, "https://www.dumate.cn")) {
            if (origin != null) old.addProperty("domain", origin)
            val restored = DumateAccountProtocol.restore(old)
            assertEquals("https://www.dumate.cn", restored.domain)
            val vault = Vault().also { it.values["provider"] = old.toString() }
            val manager = AccountProviderManager(vault, OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).addInterceptor { throw AssertionError("Old cookie must never be sent") }.build())
            assertEquals(1, manager.accounts("provider", kind).size)
            val unchanged = vault.values["provider"]
            try { manager.refresh("provider", kind); fail("Relogin is required") }
            catch (error: AccountProviderException) { assertEquals("login_required", error.errorCode) }
            assertEquals(unchanged, vault.values["provider"])
        }
        val web = MockWebServer(); val gateway = MockWebServer(); web.start(); gateway.start()
        try {
            val vault = Vault().also { it.values["provider"] = old.toString() }
            val manager = newManager(web, gateway, vault)
            enqueueLogin(web, gateway)
            login(manager)
            assertEquals(2, manager.accounts("provider", kind).size)
        } finally { web.shutdown(); gateway.shutdown() }
    }

    @Test fun explicitWebsiteSessionValidatesStableIdentityAndGatewayBeforePoolAppend() = runBlocking {
        MockWebServer().use { web -> MockWebServer().use { gateway ->
            val vault = Vault()
            val manager = newManager(web, gateway, vault)
            enqueueLogin(web, gateway)
            login(manager)
            assertEquals("/api/dumate/app/token/get", web.takeRequest().path)
            assertEquals("/api/dumate/user/info", web.takeRequest().path)
            val directory = gateway.takeRequest()
            assertEquals("/gateway/apis/v1/models", directory.path)
            assertEquals("Bearer fixture-short-ticket", directory.getHeader("Authorization"))
            assertEquals("web", directory.getHeader("X-Dumate-Client-Type"))
            assertEquals("fixture-stable-user", manager.credential("provider", kind).userId)
            assertEquals(cookie, manager.credential("provider", kind).webCookie)
            assertEquals(listOf(1), manager.accounts("provider", kind).map { it.position })
            val restored = newManager(web, gateway, vault)
            assertEquals(cookie, restored.credential("provider", kind).webCookie)
            gateway.enqueue(MockResponse().setBody(catalog))
            val snapshot = restored.snapshot("provider", kind)
            assertNull(snapshot.balance)
            assertEquals(DumateAccountProtocol.BALANCE_UNSUPPORTED, snapshot.balanceError)
            assertEquals(AccountCheckInStatus.UNSUPPORTED, manager.checkIn("provider", kind).status)
            assertEquals(2, web.requestCount)
            assertEquals(2, gateway.requestCount)
        } }
    }

    @Test fun nativeJsonAndSsePreserveRawToolsAndNeverSendWebsiteCookieToGateway() = runBlocking {
        MockWebServer().use { web -> MockWebServer().use { gateway ->
            val manager = newManager(web, gateway)
            enqueueLogin(web, gateway)
            login(manager)
            web.takeRequest(); web.takeRequest(); gateway.takeRequest()
            for (stream in listOf(false, true)) {
                gateway.enqueue(MockResponse().setBody(catalog))
                val answer = if (stream) "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"raw-call\",\"type\":\"function\",\"function\":{\"name\":\"fixture_tool\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
                    else """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"raw-call","type":"function","function":{"name":"fixture_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
                gateway.enqueue(MockResponse().setHeader("Content-Type", if (stream) "text/event-stream" else "application/json").setBody(answer))
                val json = """{"model":"fixture-directory-model","messages":[{"role":"user","content":"hi"}],"stream":$stream,"tools":[{"type":"function","function":{"name":"fixture_tool","parameters":{"type":"object"}}}],"tool_choice":"auto","parallel_tool_calls":true,"vendor_extension":{"raw":42}}"""
                val client = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).addInterceptor(manager.interceptor()).build()
                val request = Request.Builder().url(manager.baseUrl(kind, "provider") + "/chat/completions")
                    .header("Authorization", "fixture-untrusted-override").header("Cookie", "fixture-untrusted-cookie")
                    .post(json.toRequestBody()).build()
                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    val body = response.body!!.string()
                    assertTrue(body.contains("raw-call"))
                    if (stream) assertTrue(body.contains("[DONE]"))
                }
                gateway.takeRequest()
                val chat = gateway.takeRequest()
                assertEquals("/gateway/apis/v1/chat/completions", chat.path)
                assertNull(chat.getHeader("Cookie"))
                assertEquals("Bearer fixture-short-ticket", chat.getHeader("Authorization"))
                assertEquals(JsonParser.parseString(json), JsonParser.parseString(chat.body.readUtf8()))
            }
        } }
    }

    @Test fun revokedShortTicketRefreshesUsingCookieExactlyOnceWithoutLoginReplay() = runBlocking {
        MockWebServer().use { web -> MockWebServer().use { gateway ->
            val manager = newManager(web, gateway)
            enqueueLogin(web, gateway)
            login(manager)
            gateway.enqueue(MockResponse().setResponseCode(401).setBody("fixture-sensitive-echo"))
            web.enqueue(MockResponse().setBody(ticket("fixture-refreshed-ticket")))
            web.enqueue(MockResponse().setBody(user))
            gateway.enqueue(MockResponse().setBody(catalog))
            manager.authorized("provider", kind, DumateAccountProtocol.GATEWAY + "/models",
                extraHeaders = mapOf("aUtHoRiZaTiOn" to "fixture-override", "Cookie" to "fixture-override")).close()
            assertEquals("fixture-refreshed-ticket", manager.credential("provider", kind).accessToken)
            assertEquals(4, web.requestCount)
            assertEquals(3, gateway.requestCount)
            assertTrue(manager.accounts("provider", kind).size == 1)
        } }
    }

    @Test fun loginOriginStateIdentityExpiryAndLogoutRejectLateCookies() = runBlocking {
        MockWebServer().use { web -> MockWebServer().use { gateway ->
            val manager = newManager(web, gateway)
            val attempt = manager.beginLogin("provider", kind)
            for (origin in listOf("https://www.dumate.cn/", "http://dumate.baidu.com", "https://dumate.baidu.com:444", "https://user@dumate.baidu.com", "https://dumate.baidu.com.evil", "https://www.dumate.cn:444", "https://user@www.dumate.cn", "https://@www.dumate.cn", "https://www.dumate.cn.evil")) {
                assertFalse(manager.acceptWebLogin(attempt, origin, attempt.id, attempt.state, cookie))
            }
            assertFalse(manager.acceptWebLogin(attempt, DumateAccountProtocol.WEB, "wrong-id", attempt.state, cookie))
            assertFalse(manager.acceptWebLogin(attempt, DumateAccountProtocol.WEB, attempt.id, "wrong-state", cookie))
            assertFalse(attempt.acceptWebCookie(DumateAccountProtocol.WEB, attempt.id, attempt.state, cookie, attempt.expiresAt))
            manager.logout("provider")
            assertFalse(manager.acceptWebLogin(attempt, DumateAccountProtocol.WEB, attempt.id, attempt.state, cookie))
            assertFalse(manager.isLoggedIn("provider"))
            assertEquals(0, web.requestCount)
            assertEquals(0, gateway.requestCount)
        } }
    }

    @Test fun emptyDirectoryOrExpiredCookieNeverAppendsAndErrorsAreRedacted() = runBlocking {
        for (expiredCookie in listOf(false, true)) MockWebServer().use { web -> MockWebServer().use { gateway ->
            val manager = newManager(web, gateway)
            if (expiredCookie) web.enqueue(MockResponse().setResponseCode(401).setBody(cookie))
            else enqueueLogin(web, gateway, """{"data":[]}""")
            try { login(manager); fail("Invalid session must not append") }
            catch (error: AccountProviderException) { assertFalse(error.message.orEmpty().contains(cookie)) }
            assertFalse(manager.isLoggedIn("provider"))
        } }
    }

    @Test fun cancelledOrLoggedOutValidationCannotAppendLateSuccess() = runBlocking {
        for (cancel in listOf(false, true)) MockWebServer().use { web -> MockWebServer().use { gateway ->
            val manager = newManager(web, gateway)
            web.enqueue(MockResponse().setBody(ticket()).setBodyDelay(250, TimeUnit.MILLISECONDS))
            web.enqueue(MockResponse().setBody(user))
            gateway.enqueue(MockResponse().setBody(catalog))
            val attempt = manager.beginLogin("provider", kind)
            assertTrue(manager.acceptWebLogin(attempt, DumateAccountProtocol.WEB, attempt.id, attempt.state, cookie))
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                try { manager.awaitLogin(attempt) } catch (_: java.io.IOException) { }
            }
            assertNotNull(web.takeRequest(1, TimeUnit.SECONDS))
            if (cancel) operation.cancel() else manager.logout("provider")
            try { operation.await() } catch (_: kotlinx.coroutines.CancellationException) { }
            assertFalse(manager.isLoggedIn("provider"))
        } }
    }

    @Test fun crossProviderKindAndNonOfficialGatewayAreRejectedBeforeHttp() = runBlocking {
        MockWebServer().use { web -> MockWebServer().use { gateway ->
            val manager = newManager(web, gateway)
            enqueueLogin(web, gateway)
            login(manager)
            val saved = manager.exportCredentials("provider")
            try { manager.importCredentials("other", AccountProviderKind.RACCOON, saved); fail("Cross-provider import") }
            catch (error: AccountProviderException) { assertEquals("account_mismatch", error.errorCode) }
            for (url in listOf("https://www.dumate.cn/api/dumate/user/info", "https://evil.example/gateway/apis/v1/models", DumateAccountProtocol.GATEWAY + "/models?redirect=evil")) {
                try { manager.authorized("provider", kind, url).close(); fail("Nonofficial gateway") }
                catch (error: AccountProviderException) { assertEquals("invalid_gateway", error.errorCode) }
            }
            assertEquals(2, web.requestCount)
            assertEquals(1, gateway.requestCount)
        } }
    }
    @Test fun expiredStoredTicketRefreshesBeforeSendingGatewayRequest() = runBlocking {
        MockWebServer().use { web -> MockWebServer().use { gateway ->
            val manager = newManager(web, gateway)
            enqueueLogin(web, gateway)
            login(manager)
            val saved = JsonParser.parseString(manager.exportCredentials("provider")).asJsonObject
            saved.getAsJsonArray("accounts")[0].asJsonObject.addProperty("expiresAt", 1L)
            manager.importCredentials("provider", kind, saved.toString())
            web.enqueue(MockResponse().setBody(ticket("fixture-fresh-expiry-ticket")))
            web.enqueue(MockResponse().setBody(user))
            gateway.enqueue(MockResponse().setBody(catalog))
            manager.authorized("provider", kind, DumateAccountProtocol.GATEWAY + "/models").close()
            gateway.takeRequest()
            assertEquals("Bearer fixture-fresh-expiry-ticket", gateway.takeRequest().getHeader("Authorization"))
            assertEquals(4, web.requestCount)
            assertEquals(2, gateway.requestCount)
        } }
    }

    @Test fun missingExpiryOrStableIdentityNeverBecomesAnAccount() = runBlocking {
        for (missingExpiry in listOf(false, true)) MockWebServer().use { web -> MockWebServer().use { gateway ->
            val manager = newManager(web, gateway)
            web.enqueue(MockResponse().setBody(if (missingExpiry) """{"result":{"token":"fixture-no-expiry"}}""" else ticket()))
            if (!missingExpiry) web.enqueue(MockResponse().setBody("""{"result":{"nickname":"must-not-guess-identity"}}"""))
            try { login(manager); fail("Missing evidence") }
            catch (error: AccountProviderException) { assertEquals("invalid_credentials", error.errorCode) }
            assertFalse(manager.isLoggedIn("provider"))
            assertEquals(0, gateway.requestCount)
        } }
    }

}
