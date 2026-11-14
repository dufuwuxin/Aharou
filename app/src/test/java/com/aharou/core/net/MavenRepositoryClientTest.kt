package com.aharou.core.net

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenRepositoryClientTest {
    @Test fun redirectToPrivateAddressNeverReachesTransport() {
        for (target in listOf("http://127.0.0.1/private", "http://10.0.0.1/private", "http://[fd00::1]/private")) {
            val requests = mutableListOf<String>()
            val client = client(requests) { target }
            val failure = runCatching { executeSafeMavenRequest(client, request()).close() }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(listOf("https://8.8.8.8/metadata"), requests)
        }
    }

    @Test fun relativeRedirectAndPublicHopAreFollowedAndCheckedAgain() {
        val requests = mutableListOf<String>()
        val client = client(requests) { index ->
            when (index) {
                1 -> "/next"
                2 -> "https://1.1.1.1/final"
                else -> null
            }
        }
        executeSafeMavenRequest(client, request()).use { assertEquals(200, it.code) }
        assertEquals(listOf("https://8.8.8.8/metadata", "https://8.8.8.8/next", "https://1.1.1.1/final"), requests)
    }

    @Test fun redirectLoopIsBounded() {
        val requests = mutableListOf<String>()
        val failure = runCatching {
            executeSafeMavenRequest(client(requests) { "/metadata" }, request()).close()
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(11, requests.size)
    }

    private fun request() = Request.Builder().url("https://8.8.8.8/metadata").build()

    // Application interceptor returns every response without opening sockets or resolving DNS.
    private fun client(requests: MutableList<String>, location: (Int) -> String?) =
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                requests += chain.request().url.toString()
                val target = location(requests.size)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (target == null) 200 else 302).message("fixture")
                    .body("metadata".toResponseBody())
                    .apply { if (target != null) header("Location", target) }.build()
            }.build()
}
