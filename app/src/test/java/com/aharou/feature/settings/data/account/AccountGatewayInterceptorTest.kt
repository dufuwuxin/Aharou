package com.aharou.feature.settings.data.account

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AccountGatewayInterceptorTest {
    private class Vault : AccountCredentialVault {
        val values = ConcurrentHashMap<String, String>()
        override fun read(id: String) = values[id]
        override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
    }

    private fun manager(server: MockWebServer, vault: Vault = Vault()): AccountProviderManager =
        AccountProviderManager(vault, OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(), AccountEndpoints(raccoon = server.url("/").toString().trimEnd('/')))

    private fun seed(manager: AccountProviderManager) {
        manager.importCredentials("id", AccountProviderKind.RACCOON, """{"accessToken":"fixture-access","refreshToken":"fixture-refresh"}""")
    }

    @Test fun streamAndToolsUseSeparateAuthenticatedTransport() {
        MockWebServer().use { server ->
            val manager = manager(server)
            seed(manager)
            server.enqueue(MockResponse().setBody("""{"data":{"categories":[{"type":"chat","models":[{"name":"m"}]}]}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n"))
            val client = OkHttpClient.Builder().addInterceptor(manager.interceptor()).build()
            val request = Request.Builder().url(manager.baseUrl(AccountProviderKind.RACCOON, "id") + "/chat/completions").post("""{"model":"m","stream":true,"messages":[{"role":"tool","tool_call_id":"c","content":"result"}]}""".toRequestBody()).build()
            client.newCall(request).execute().use { assertTrue(it.body!!.string().contains("[DONE]")) }
            val catalog = server.takeRequest(1, TimeUnit.SECONDS)!!
            val chat = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("Bearer fixture-access", catalog.getHeader("Authorization"))
            assertEquals("/api/web/llm/v2/chat/completions", chat.path)
            assertTrue(chat.body.readUtf8().contains("tool_call_id"))
        }
    }

    @Test fun unknownModelAndRedirectAreRejected() {
        MockWebServer().use { server ->
            val manager = manager(server)
            seed(manager)
            server.enqueue(MockResponse().setBody("""{"data":{"categories":[]}}"""))
            val client = OkHttpClient.Builder().addInterceptor(manager.interceptor()).build()
            val request = Request.Builder().url(manager.baseUrl(AccountProviderKind.RACCOON, "id") + "/chat/completions").post("""{"model":"unknown","messages":[]}""".toRequestBody()).build()
            client.newCall(request).execute().use { assertEquals(400, it.code) }
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/credential-sink"))
            try { manager.catalog("id", AccountProviderKind.RACCOON); fail("No redirects") } catch (_: AccountProviderException) { }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun concurrentRefreshRotatesBothTokensOnlyOnce() {
        MockWebServer().use { server ->
            val manager = manager(server)
            seed(manager)
            server.enqueue(MockResponse().setBody("""{"data":{"access_token":"next-access","refresh_token":"next-refresh"}}"""))
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<String> { manager.refresh("id", AccountProviderKind.RACCOON, "fixture-access").accessToken }
                val second = executor.submit<String> { manager.refresh("id", AccountProviderKind.RACCOON, "fixture-access").accessToken }
                assertEquals("next-access", first.get(3, TimeUnit.SECONDS))
                assertEquals("next-access", second.get(3, TimeUnit.SECONDS))
                val exported = com.google.gson.JsonParser.parseString(manager.exportCredentials("id")).asJsonObject
                assertEquals("next-refresh", exported.get("refreshToken").asString)
                assertEquals(1, server.requestCount)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun refreshRotationCannotResurrectLoggedOutAccount() {
        MockWebServer().use { server ->
            val vault = Vault()
            val manager = manager(server, vault)
            seed(manager)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    return MockResponse().setBody("""{"code":0,"data":{"access_token":"next-access","refresh_token":"next-refresh"}}""")
                }
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Boolean> {
                    try { manager.refresh("id", AccountProviderKind.RACCOON, "fixture-access"); false } catch (_: AccountProviderException) { true }
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                manager.logout("id")
                release.countDown()
                assertTrue(future.get(3, TimeUnit.SECONDS))
                assertFalse(manager.isLoggedIn("id"))
                assertNull(vault.read("id"))
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }
}
