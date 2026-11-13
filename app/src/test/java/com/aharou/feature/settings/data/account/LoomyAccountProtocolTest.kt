package com.aharou.feature.settings.data.account

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LoomyAccountProtocolTest {
    private class Vault : AccountCredentialVault {
        private val values = mutableMapOf<String, String>()
        override fun read(id: String) = values[id]
        override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
    }
    @Test fun validatedSessionUsesProtocolCatalogBalanceAndStreamingRoutes() = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).followRedirects(false).build(), AccountEndpoints(loomy = base))
            val catalog = """{"code":"000000","data":[{"id":"m","name":"Model"}]}"""
            server.enqueue(MockResponse().setBody(catalog))
            manager.importLoomySession("id", "fixture-session")
            val validation = server.takeRequest()
            assertEquals("/api/v1/models", validation.path)
            assertEquals("fixture-session", validation.getHeader("token"))
            assertEquals("0.9.38", validation.getHeader("loomy-version"))
            server.enqueue(MockResponse().setBody(catalog))
            server.enqueue(MockResponse().setBody("""{"code":"000000","data":{"balance":12}}"""))
            assertEquals("12 points", manager.snapshot("id", AccountProviderKind.LOOMY).balance)
            server.takeRequest()
            assertEquals("/api/v1/points/records?pageNo=1&pageSize=20&recordType=all", server.takeRequest().path)
            for (stream in listOf(true, false)) {
                server.enqueue(MockResponse().setBody(catalog))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                val client = OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).addInterceptor(manager.interceptor()).build()
                val request = Request.Builder().url(manager.baseUrl(AccountProviderKind.LOOMY, "id") + "/chat/completions")
                    .post("""{"model":"m","messages":[{"role":"user","content":"hi"}],"stream":$stream}""".toRequestBody()).build()
                client.newCall(request).execute().use { response -> assertEquals(200, response.code); assertTrue(response.body!!.string().contains("hello")) }
                server.takeRequest()
                val inference = server.takeRequest()
                assertEquals("/api/v1/chat/completions", inference.path)
                assertEquals("Bearer fixture-session", inference.getHeader("Authorization"))
                assertEquals("user", inference.getHeader("invokeorigin"))
                assertTrue(inference.body.readUtf8().contains("\"stream\":true"))
            }
        }
    }
    @Test fun nonZeroBusinessCodeDoesNotBecomeSuccessfulSessionImport() = runBlocking {
        MockWebServer().use { server ->
            val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).build(), AccountEndpoints(loomy = server.url("/").toString().trimEnd('/')))
            server.enqueue(MockResponse().setBody("""{"code":"000001","data":[{"id":"m"}]}"""))
            try { manager.importLoomySession("id", "fixture-invalid"); fail("Nonzero business code must fail") }
            catch (_: AccountProviderException) { assertFalse(manager.isLoggedIn("id")) }
        }
    }

    @Test fun invalidSessionDoesNotReplaceSavedCredential() = runBlocking {
        MockWebServer().use { server ->
            val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).build(), AccountEndpoints(loomy = server.url("/").toString().trimEnd('/')))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":12153}"""))
            try { manager.importLoomySession("id", "fixture-invalid"); fail("Validate before save") }
            catch (_: AccountProviderException) { assertFalse(manager.isLoggedIn("id")) }
        }
    }
    @Test fun logoutOrCancellationRejectsPendingSessionImport() = runBlocking {
        for (cancel in listOf(false, true)) {
            MockWebServer().use { server ->
                val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).build(), AccountEndpoints(loomy = server.url("/").toString().trimEnd('/')))
                server.enqueue(MockResponse().setBody("""{"code":0,"data":[{"id":"m"}]}""").setBodyDelay(250, java.util.concurrent.TimeUnit.MILLISECONDS))
                val operation = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    try { manager.importLoomySession("id", "fixture-session") }
                    catch (_: java.io.IOException) { }
                }
                assertNotNull(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
                if (cancel) operation.cancel() else manager.logout("id")
                try { operation.await() } catch (_: kotlinx.coroutines.CancellationException) { }
                assertFalse(manager.isLoggedIn("id"))
            }
        }
    }

}
