package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.openai.OpenAIApi
import com.google.gson.JsonObject
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CodexCacheIdentityTest {
    @Test fun providerAndSessionBoundariesCannotCollide() = runTest {
        val api = mockk<OpenAIApi>()
        val headers = mutableListOf<Map<String, String>>()
        val requests = mutableListOf<Map<String, Any?>>()
        coEvery { api.createResponses(any(), any(), capture(headers), capture(requests)) } returns JsonObject().apply {
            addProperty("status", "completed")
        }
        for ((provider, session) in listOf("a:b" to "c", "a" to "b:c", "a:b" to "c")) {
            OpenAIAdapter(api).apply {
                providerId = provider
                logSessionId = session
                model = "model"
                useResponseApi = true
                customHeaders = mapOf("originator" to "codex_cli_rs")
            }.complete("stable", emptyList())
        }
        assertNotEquals(headers[0]["session-id"], headers[1]["session-id"])
        assertEquals(requests[0]["prompt_cache_key"], requests[2]["prompt_cache_key"])
        assertNotEquals(requests[0]["prompt_cache_key"], requests[1]["prompt_cache_key"])
    }

    @Test fun recreatedAdaptersKeepTheSameSessionButEachRequestHasItsOwnTurn() = runTest {
        val api = mockk<OpenAIApi>()
        val headers = mutableListOf<Map<String, String>>()
        val requests = mutableListOf<Map<String, Any?>>()
        coEvery { api.createResponses(any(), any(), capture(headers), capture(requests)) } returns JsonObject().apply {
            addProperty("status", "completed")
        }
        for (session in listOf("session-one", "session-one", "session-two")) {
            OpenAIAdapter(api).apply {
                model = "model"
                logSessionId = session
                useResponseApi = true
                customHeaders = mapOf("originator" to "codex_cli_rs")
            }.complete("stable", emptyList())
        }
        assertEquals(headers[0]["session-id"], headers[1]["session-id"])
        assertNotEquals(headers[0]["session-id"], headers[2]["session-id"])
        val metadata = requests.map { it.getValue("client_metadata") as Map<*, *> }
        assertEquals(metadata[0]["x-codex-installation-id"], metadata[1]["x-codex-installation-id"])
        assertNotEquals(metadata[0]["turn_id"], metadata[1]["turn_id"])
        assertEquals(listOf("session-one", "session-one", "session-two"), requests.map { it["prompt_cache_key"] })
        headers.indices.forEach { index -> assertEquals(headers[index]["session-id"], metadata[index]["session_id"]) }
    }
}
