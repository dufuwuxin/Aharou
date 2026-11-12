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
            val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().followRedirects(false).build(), AccountEndpoints(workbuddy = base, workbuddyBilling = base))
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

    @Test fun restrictedAccountIsNotClassifiedAsExpiredLogin() {
        try {
            AccountProviderProtocol.check(json("""{"code":11140}"""))
            fail("Account restriction must fail")
        } catch (error: AccountProviderException) {
            assertEquals("account_restricted", error.errorCode)
            assertFalse(error.httpStatus == 401)
        }
    }
}
