package com.aharou.feature.settings.data.account

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class TraeLoginCallbackTest {
    private class Vault : AccountCredentialVault {
        override fun read(id: String): String? = null
        override fun write(id: String, value: String?) = Unit
    }

    @Test fun officialPcCallbackEncodingAndTraceAreBoundToCurrentPending() = runBlocking {
        val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).build())
        val login = manager.beginLogin("provider", AccountProviderKind.TRAE)
        try {
            val authorization = login.url.toHttpUrl()
            assertEquals(login.callbackPrefix, authorization.queryParameter("auth_callback_url"))
            assertEquals(login.state, authorization.queryParameter("login_trace_id"))
            assertNull(authorization.queryParameter("state"))
            val callback = login.callbackPrefix!!.toHttpUrl().newBuilder()
                .addQueryParameter("authCodeInfo", """{"AuthCode":"fixture-pc-code"}""")
                .addQueryParameter("loginTraceID", login.state).build()
            for (url in listOf(
                callback.newBuilder().removeAllQueryParameters("loginTraceID").build(),
                callback.newBuilder().setQueryParameter("loginTraceID", "wrong").build(),
                callback.newBuilder().addQueryParameter("state", "wrong").build(),
                callback.newBuilder().addQueryParameter("loginTraceID", login.state).build(),
                callback.newBuilder().addQueryParameter("error", "cancelled").build(),
                callback.newBuilder().addQueryParameter("access_token", "fixture-token").build(),
                callback.newBuilder().setQueryParameter("authCodeInfo", "not-json").build(),
                callback.newBuilder().host("evil.example").build(),
                callback.newBuilder().encodedPath("/authorize/other").build(),
            )) assertFalse(login.accept(url.toString(), System.currentTimeMillis()))
            assertFalse(login.accept(callback.toString(), login.expiresAt))
            assertTrue(login.accept(callback.toString(), System.currentTimeMillis()))
            assertEquals("fixture-pc-code", login.code)
            assertFalse(login.accept(callback.toString(), System.currentTimeMillis()))
        } finally { manager.cancelLogin(login) }
    }

    @Test fun officialRefreshCallbackExchangesRotatesAndValidatesIdentity() = runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"Result":{"Token":"fixture-access","RefreshToken":"fixture-rotated","TokenExpireAt":4102444800}}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"Result":{"UserID":"verified-user","EnterpriseID":"verified-tenant"}}"""))
            val values = mutableMapOf<String, String>()
            val vault = object : AccountCredentialVault {
                override fun read(id: String) = values[id]
                override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
            }
            val manager = AccountProviderManager(vault, OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).build(), AccountEndpoints(traeOAuth = server.url("/").toString().trimEnd('/')))
            val login = manager.beginLogin("provider", AccountProviderKind.TRAE)
            try {
                val authorization = login.url.toHttpUrl()
                assertEquals("https://api.trae.com.cn", AccountEndpoints().traeOAuth(AccountProviderKind.TRAE))
                assertEquals("https://api.trae.ai", AccountEndpoints().traeOAuth(AccountProviderKind.TRAE_GLOBAL))
                assertEquals("solo", authorization.queryParameter("auth_from"))
                assertEquals("en1oxy7wnw8j9n", authorization.queryParameter("client_id"))
                assertFalse(authorization.queryParameter("plugin_version").isNullOrBlank())
                assertEquals(login.deviceId, authorization.queryParameter("x_device_id"))
                val callback = login.callbackPrefix!!.toHttpUrl().newBuilder()
                    .addQueryParameter("refreshToken", "fixture-refresh")
                    .addQueryParameter("loginTraceID", login.state).build()
                assertFalse(manager.acceptCallback(login, callback.newBuilder().setQueryParameter("loginTraceID", "wrong").build().toString()))
                assertTrue(manager.acceptCallback(login, callback.toString()))
                manager.awaitLogin(login)
                val exchange = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
                assertEquals("/cloudide/api/v3/trae/oauth/ExchangeToken", exchange.path)
                val body = com.google.gson.JsonParser.parseString(exchange.body.readUtf8()).asJsonObject
                assertEquals("fixture-refresh", body.text("RefreshToken"))
                assertEquals("en1oxy7wnw8j9n", body.text("ClientID"))
                assertEquals("Trae/0.1.52", exchange.getHeader("User-Agent"))
                assertFalse(body.has("AuthCode"))
                val identity = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
                assertEquals("/cloudide/api/v3/trae/GetUserInfo", identity.path)
                assertEquals("fixture-access", identity.getHeader("X-Cloudide-Token"))
                val saved = com.google.gson.JsonParser.parseString(manager.exportCredentials("provider")).asJsonObject
                assertEquals("verified-user", saved.text("userId"))
                assertEquals("fixture-rotated", saved.text("refreshToken"))
                assertEquals("trae-solo", saved.text("domain"))
                server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"Result":{"Token":"fixture-access-next","RefreshToken":"fixture-rotated-next","TokenExpireAt":4102444800}}"""))
                manager.refresh("provider", AccountProviderKind.TRAE, "fixture-access")
                val refreshed = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
                assertEquals("/cloudide/api/v3/trae/oauth/ExchangeToken", refreshed.path)
                assertEquals("Trae/0.1.52", refreshed.getHeader("User-Agent"))
                assertEquals("fixture-rotated", com.google.gson.JsonParser.parseString(refreshed.body.readUtf8()).asJsonObject.text("RefreshToken"))
                assertNull(login.traeTokens)
                assertFalse(manager.acceptCallback(login, callback.toString()))
            } finally { manager.cancelLogin(login) }
        }
    }

    @Test fun authorizationCodeKeepsTheClientAndOAuthHostOfItsLogin() = runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { oauth ->
            okhttp3.mockwebserver.MockWebServer().use { usage ->
                oauth.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"Result":{"Token":"fixture-access","RefreshToken":"fixture-refresh","UserID":"fixture-user"}}"""))
                val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).build(),
                    AccountEndpoints(traeOAuth = oauth.url("/").toString().trimEnd('/'), traeAccount = usage.url("/").toString().trimEnd('/')))
                val login = manager.beginLogin("provider", AccountProviderKind.TRAE)
                try {
                    val callback = login.callbackPrefix!!.toHttpUrl().newBuilder().addQueryParameter("loginTraceID", login.state)
                        .addQueryParameter("authCodeInfo", """{"AuthCode":"fixture-code"}""").build()
                    assertTrue(manager.acceptCallback(login, callback.toString()))
                    val credential = AccountProviderLogin.await(manager, login)
                    val request = oauth.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
                    val body = com.google.gson.JsonParser.parseString(request.body.readUtf8()).asJsonObject
                    assertEquals("/trae/api/v3/oauth/ExchangeToken", request.path)
                    assertEquals(login.url.toHttpUrl().queryParameter("client_id"), body.text("ClientID"))
                    assertEquals("fixture-code", body.text("AuthCode"))
                    assertEquals("Trae/0.1.52", request.getHeader("User-Agent"))
                    assertEquals("trae-solo", credential.domain)
                    assertEquals(0, usage.requestCount)
                } finally { manager.cancelLogin(login) }
            }
        }
    }

    @Test fun userJwtRequiresBoundTraceAndRejectsAmbiguousOrMalformedTokens() = runBlocking {
        val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).build())
        val login = manager.beginLogin("provider", AccountProviderKind.TRAE_GLOBAL)
        try {
            val callback = login.callbackPrefix!!.toHttpUrl().newBuilder().addQueryParameter("loginTraceID", login.state)
                .addQueryParameter("userJwt", """{"Token":"fixture-access","TokenExpireAt":4102444800}""").build()
            for (bad in listOf(
                callback.newBuilder().setQueryParameter("userJwt", "not-json").build(),
                callback.newBuilder().setQueryParameter("userJwt", """{"RefreshToken":"fixture-nested"}""").addQueryParameter("refreshToken", "different").build(),
                callback.newBuilder().addQueryParameter("error", "denied").build(),
                callback.newBuilder().setQueryParameter("userJwt", """{"Token":"fixture-access","TokenExpireAt":1}""").build(),
                callback.newBuilder().addQueryParameter("userJwt", "{}").build(),
            )) assertFalse(manager.acceptCallback(login, bad.toString()))
            assertTrue(manager.acceptCallback(login, callback.toString()))
            assertEquals("fixture-access", login.traeTokens?.accessToken)
            assertFalse(login.toString().contains("fixture-access"))
            manager.cancelLogin(login)
            assertNull(login.traeTokens)
        } finally { manager.cancelLogin(login) }
    }

    @Test fun actualLoopbackHttpRequestParsesOfficialPcPayload() = runBlocking {
        val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).build())
        val login = manager.beginLogin("provider", AccountProviderKind.TRAE)
        try {
            val callback = login.callbackPrefix!!.toHttpUrl().newBuilder()
                .addQueryParameter("loginTraceID", login.state)
                .addQueryParameter("authCodeInfo", """{"Code":"fixture-http-code"}""").build()
            val method = AccountProviderLogin::class.java.getDeclaredMethod("readLoopback", AccountLogin::class.java).apply { isAccessible = true }
            val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
            val operation = reader.submit { method.invoke(AccountProviderLogin, login) }
            try {
            Socket("127.0.0.1", callback.port).use { socket ->
                socket.soTimeout = 2000
                val path = callback.encodedPath + "?" + callback.encodedQuery
                socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("200 OK"))
            }
            operation.get(3, java.util.concurrent.TimeUnit.SECONDS)
            } finally { reader.shutdownNow() }
            assertEquals("fixture-http-code", login.code)
            manager.cancelLogin(login)
            assertFalse(login.accept(callback.toString(), System.currentTimeMillis()))
        } finally { manager.cancelLogin(login) }
    }
}
