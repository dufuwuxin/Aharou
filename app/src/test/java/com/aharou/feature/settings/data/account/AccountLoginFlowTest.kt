package com.aharou.feature.settings.data.account

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AccountLoginFlowTest {
    private class Vault : AccountCredentialVault {
        private val values = ConcurrentHashMap<String, String>()
        override fun read(id: String): String? = values[id]
        override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
    }
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject

    @Test fun loginCookieIsIsolatedAndAccountIdentityIsSaved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "route=one; Path=/").setBody("""{"code":0,"data":{"state":"fixture-state","authUrl":"https://copilot.tencent.com/login"}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"accessToken":"fixture-token","refreshToken":"fixture-refresh","domain":"saas","expiresIn":3600}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"uid":"fixture-user","enterpriseId":"fixture-enterprise"}}"""))
            val base = server.url("/").toString().trimEnd('/')
            val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).followRedirects(false).build(), AccountEndpoints(workbuddy = base, workbuddyBilling = base))
            val login = manager.beginLogin("one", AccountProviderKind.WORKBUDDY)
            try {
                manager.awaitLogin(login)
                assertTrue(manager.isLoggedIn("one"))
                val saved = json(manager.exportCredentials("one"))
                assertEquals("fixture-user", saved.get("userId").asString)
                assertEquals("fixture-enterprise", saved.get("enterpriseId").asString)
                assertEquals("saas", saved.get("domain").asString)
                assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Cookie"))
                assertEquals("route=one", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Cookie"))
                assertEquals("route=one", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Cookie"))
                assertTrue(login.cookies.headers(base).isEmpty())
                assertTrue(login.cookies.headers("https://unrelated.example").isEmpty())
            } finally { manager.cancelLogin(login) }
        }
    }

    @Test fun cookieCannotBeSentToAnotherHost() {
        val jar = LoginCookies()
        val request = okhttp3.Request.Builder().url("https://copilot.tencent.com/v2/plugin/auth/state").build()
        val response = okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
            .header("Set-Cookie", "route=fixture; Path=/; Secure").build()
        jar.absorb(response)
        assertEquals("route=fixture", jar.headers("https://copilot.tencent.com/v2/plugin/auth/token")["Cookie"])
        assertTrue(jar.headers("https://www.workbuddy.cn/v2/plugin/auth/token").isEmpty())
        assertTrue(jar.headers("http://copilot.tencent.com/v2/plugin/auth/token").isEmpty())
    }

    @Test fun realCatalogShapePreservesDecimalAndFiltersCliModels() {
        val models = AccountProviderProtocol.models(AccountProviderKind.WORKBUDDY, json("""{"data":{"models":[{"id":"paid","credits":"x0.29"},{"id":"free","credits":"x0 credits"},{"id":"other","credits":"x1"}],"agent":{"agents":[{"name":"cli","models":["paid","free"]}]}}}"""))
        assertEquals(listOf("paid", "free"), models.map { it.id })
        assertEquals("0.29", models[0].multiplier)
        assertEquals("0", models[1].multiplier)
        assertEquals("2.75 / 10 cycle credits", AccountProviderProtocol.balance(AccountProviderKind.WORKBUDDY, json("""{"data":{"Response":{"Data":{"Accounts":[{"CycleCapacitySizePrecise":"10","CycleCapacityRemainPrecise":"2.75","CapacityRemainPrecise":"1000"}]}}}}""")))
        assertNull(AccountProviderProtocol.balance(AccountProviderKind.WORKBUDDY, json("""{"data":{"Accounts":[{"CapacityRemainPrecise":"1000"}]}}""")))
    }

    @Test fun nativeToolIdsRemainDistinctAcrossFramesAndFinalUsageSurvives() {
        val events = listOf(
            "event: delta\ndata: {\"tool_calls\":[{\"id\":\"first\",\"function_call\":{\"name\":\"one\",\"arguments\":\"{\"}}]}\n\n",
            "event: delta\ndata: {\"tool_calls\":[{\"id\":\"second\",\"function_call\":{\"name\":\"two\",\"arguments\":\"{}\"}}]}\n\n",
            "event: delta\ndata: {\"tool_calls\":[{\"id\":\"first\",\"function_call\":{\"partial_arguments\":\"}\"}}],\"finish_reason\":\"tool_calls\"}\n\n",
            "event: token_usage\ndata: {\"usage\":{\"prompt_tokens\":5}}\n\n",
            "event: response_done\ndata: {\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":7}}\n\n",
        ).joinToString("")
        val result = json(TraeAccountProtocol.responseBody(ResponseBody.create("text/event-stream".toMediaType(), events), "m", false).string())
        val message = result.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
        val calls = message.getAsJsonArray("tool_calls")
        assertEquals(2, calls.size())
        assertEquals("first", calls[0].asJsonObject.get("id").asString)
        assertEquals("{}", calls[0].asJsonObject.getAsJsonObject("function").get("arguments").asString)
        assertEquals("second", calls[1].asJsonObject.get("id").asString)
        assertEquals(7, result.getAsJsonObject("usage").get("completion_tokens").asInt)
    }

    @Test fun businessFailureAfterFinishReasonIsNotIgnored() {
        val events = "event: delta\ndata: {\"content\":\"partial\",\"finish_reason\":\"stop\"}\n\nevent: error\ndata: {\"code\":4001}\n\n"
        try {
            TraeAccountProtocol.responseBody(ResponseBody.create("text/event-stream".toMediaType(), events), "m", false)
            fail("A trailing business error must not become success")
        } catch (error: AccountProviderException) { assertEquals("upstream_error", error.errorCode) }
    }

    @Test fun balanceFailureDoesNotDiscardCatalog() = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).followRedirects(false).build(), AccountEndpoints(raccoon = base))
            manager.importCredentials("id", AccountProviderKind.RACCOON, """{"accessToken":"fixture-access"}""")
            server.enqueue(MockResponse().setBody("""{"data":{"categories":[{"type":"chat","models":[{"name":"available"}]}]}}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":11140}"""))
            val result = manager.snapshot("id", AccountProviderKind.RACCOON)
            assertEquals(listOf("available"), result.models.map { it.id })
            assertNull(result.balance)
            assertEquals("account_restricted", result.balanceError)
        }
    }

    @Test fun logoutInvalidatesDetachedResponseBodyAndClosesBoundaryResult() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).followRedirects(false).build(), AccountEndpoints(raccoon = base))
            manager.importCredentials("id", AccountProviderKind.RACCOON, """{"accessToken":"fixture-access"}""")
            server.enqueue(MockResponse().setBody("fixture-body"))
            val response = manager.withAccountRevision("id") { manager.authorized("id", AccountProviderKind.RACCOON, base + "/stream") }
            manager.logout("id")
            try { response.use { it.body!!.string() }; fail("Detached reads after logout must fail") }
            catch (error: AccountProviderException) { assertEquals("account_changed", error.errorCode) }
            var closed = false
            try {
                manager.withAccountRevision("id") {
                    manager.logout("id")
                    java.io.Closeable { closed = true }
                }
                fail("Boundary result cannot survive a changed account")
            } catch (error: AccountProviderException) { assertEquals("account_changed", error.errorCode) }
            assertTrue(closed)
        }
    }

    @Test fun staleSnapshotCannotPersistModels() = runBlocking {
        val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).build())
        val version = manager.accountVersion("id")
        manager.logout("id")
        val repository = io.mockk.mockk<com.aharou.feature.settings.domain.repository.AIProviderRepository>()
        val config = com.aharou.feature.settings.domain.model.AIProviderConfig("id", "Account", com.aharou.feature.settings.domain.model.ProviderType.OPENAI, "", baseUrl = manager.baseUrl(AccountProviderKind.RACCOON, "id"), defaultModel = "m")
        try { manager.persistSnapshot("id", version, repository, config); fail("Stale models cannot be saved") }
        catch (error: AccountProviderException) { assertEquals("account_changed", error.errorCode) }
        io.mockk.coVerify(exactly = 0) { repository.saveProvider(any()) }
    }

    @Test fun restrictedAccountIsNotClassifiedAsExpiredLogin() {
        try {
            AccountProviderProtocol.check(json("""{"code":11140}"""))
            fail("Account restriction must fail")
        } catch (error: AccountProviderException) {
            assertEquals("account_restricted", error.errorCode)
            assertFalse(error.httpStatus == 401)
        }
    }
    @Test fun globalWorkBuddyLoginAndRefreshUseOnlyGlobalEndpoints() = runBlocking {
        MockWebServer().use { global ->
            MockWebServer().use { domestic ->
                val base = global.url("/").toString().trimEnd('/')
                val cn = domestic.url("/").toString().trimEnd('/')
                val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).followRedirects(false).build(), AccountEndpoints(workbuddy = cn, workbuddyGlobal = base))
                global.enqueue(MockResponse().setBody("""{"code":0,"data":{"state":"s","authUrl":"https://www.workbuddy.ai/login"}}"""))
                global.enqueue(MockResponse().setBody("""{"code":0,"data":{"accessToken":"fixture-a","refreshToken":"fixture-r"}}"""))
                global.enqueue(MockResponse().setBody("""{"code":0,"data":{"uid":"u"}}"""))
                val login = manager.beginLogin("id", AccountProviderKind.WORKBUDDY_GLOBAL)
                manager.awaitLogin(login)
                global.enqueue(MockResponse().setBody("""{"code":0,"data":{"accessToken":"fixture-b","refreshToken":"fixture-next"}}"""))
                manager.refresh("id", AccountProviderKind.WORKBUDDY_GLOBAL, "fixture-a")
                assertEquals("/v2/plugin/auth/state?platform=CLI", global.takeRequest().path)
                assertEquals("/v2/plugin/auth/token?state=s", global.takeRequest().path)
                assertEquals("/v2/plugin/login/account?state=s", global.takeRequest().path)
                val refresh = global.takeRequest()
                assertEquals("/v2/plugin/auth/token/refresh", refresh.path)
                assertEquals("https://www.workbuddy.ai", refresh.getHeader("Origin"))
                global.enqueue(MockResponse().setBody("""{"data":{"models":[{"id":"m"}]}}"""))
                global.enqueue(MockResponse().setBody("""{"data":{"Accounts":[{"CycleCapacitySize":100,"CycleCapacityRemain":75}]}}"""))
                assertEquals("75 / 100 cycle credits", manager.snapshot("id", AccountProviderKind.WORKBUDDY_GLOBAL).balance)
                assertEquals("/v3/config", global.takeRequest(1, TimeUnit.SECONDS)!!.path)
                assertEquals("/v2/billing/meter/get-user-resource", global.takeRequest(1, TimeUnit.SECONDS)!!.path)
                assertEquals(0, domestic.requestCount)
                try { manager.credential("id", AccountProviderKind.WORKBUDDY); fail("Cross-region credentials must fail") }
                catch (error: AccountProviderException) { assertEquals("account_mismatch", error.errorCode) }
            }
        }
    }

    @Test fun globalTraeAuthorizationUsesInternationalWebsiteAndCancellationRejectsCallback() = runBlocking {
        val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).build())
        val login = manager.beginLogin("id", AccountProviderKind.TRAE_GLOBAL)
        assertTrue(login.url.startsWith("https://www.trae.ai/authorization?"))
        manager.cancelLogin(login)
        assertFalse(manager.acceptCallback(login, login.callbackPrefix + "?code=late&state=" + login.state))
        assertFalse(manager.isLoggedIn("id"))
    }

    @Test fun globalTraeExchangeRefreshAndBalanceNeverUseDomesticHost() = runBlocking {
        MockWebServer().use { global ->
            MockWebServer().use { domestic ->
                val base = global.url("/").toString().trimEnd('/')
                val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).followRedirects(false).build(), AccountEndpoints(traeAccount = domestic.url("/").toString().trimEnd('/'), traeGlobalAccount = base, traeGlobal = base, traeGlobalUsage = base))
                val login = manager.beginLogin("id", AccountProviderKind.TRAE_GLOBAL)
                assertTrue(manager.acceptCallback(login, login.callbackPrefix + "?code=fixture-code&state=" + login.state))
                global.enqueue(MockResponse().setBody("""{"Result":{"Token":"fixture-a","RefreshToken":"fixture-r"}}"""))
                manager.awaitLogin(login)
                val exchange = global.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/trae/api/v3/oauth/ExchangeToken", exchange.path)
                assertEquals("en1oxy7wnw8j9n", json(exchange.body.readUtf8()).get("ClientID").asString)
                global.enqueue(MockResponse().setBody("""{"Result":{"Token":"fixture-b","RefreshToken":"fixture-next"}}"""))
                manager.refresh("id", AccountProviderKind.TRAE_GLOBAL, "fixture-a")
                assertEquals("/cloudide/api/v3/trae/oauth/ExchangeToken", global.takeRequest(1, TimeUnit.SECONDS)!!.path)
                global.enqueue(MockResponse().setBody("""{"data":{"config_info_list":[{"config_name":"m"}]}}"""))
                global.enqueue(MockResponse().setBody("""{"data":{"user_entitlement_pack_list":[{"entitlement_base_info":{"quota":{"credits_limit":100}},"usage":{"credits_amount":25}}]}}"""))
                assertEquals("75 / 100 credits", manager.snapshot("id", AccountProviderKind.TRAE_GLOBAL).balance)
                assertEquals("/api/ide/v1/get_detail_param", global.takeRequest(1, TimeUnit.SECONDS)!!.path)
                assertEquals("/trae/api/v1/pay/ide_user_ent_usage", global.takeRequest(1, TimeUnit.SECONDS)!!.path)
                assertEquals(0, domestic.requestCount)
            }
        }
    }

}
