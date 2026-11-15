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
