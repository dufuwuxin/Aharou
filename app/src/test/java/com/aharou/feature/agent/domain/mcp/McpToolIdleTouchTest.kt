package com.aharou.feature.agent.domain.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolIdleTouchTest {
    @Test
    fun longCallTouchesAtStartAndAgainAfterCompletion() = runTest {
        val started = CompletableDeferred<Unit>()
        val finishCall = CompletableDeferred<Unit>()
        val touches = mutableListOf<Int>()
        val activeStates = mutableListOf<Boolean>()
        val gate = McpCallEvictionGate()
        val client = McpClient("stdio", BlockingCallTransport {
            started.complete(Unit)
            finishCall.await()
            successfulCallResponse()
        })
        val tool = McpTool(
            client,
            McpToolDescriptor("long_task"),
            onExecutionStateChanged = { active ->
                activeStates += active
                if (active) gate.beginCall("stdio") else { gate.endCall("stdio"); true }
            },
            onUsed = { touches += touches.size + 1 },
        )

        val call = async { tool.execute(emptyMap()) }
        started.await()
        assertEquals(listOf(1), touches)
        assertEquals(listOf(true), activeStates)
        assertFalse(gate.claimEviction("stdio"))
        finishCall.complete(Unit)
        call.await()

        assertEquals(listOf(1, 2), touches)
        assertEquals(listOf(true, false), activeStates)
        assertTrue(gate.claimEviction("stdio"))
        gate.finishEviction("stdio")
    }

    @Test
    fun failedCallAlsoTouchesOnCompletion() = runTest {
        val touches = mutableListOf<Int>()
        val activeStates = mutableListOf<Boolean>()
        val client = McpClient("stdio", BlockingCallTransport { error("transport failed") })
        val tool = McpTool(
            client,
            McpToolDescriptor("fails"),
            onExecutionStateChanged = { activeStates += it; true },
            onUsed = { touches += touches.size + 1 },
        )

        tool.execute(emptyMap())

        assertEquals(listOf(1, 2), touches)
        assertEquals(listOf(true, false), activeStates)
    }

    @Test
    fun callAfterEvictionClaimReconnectsBeforeUsingTransport() = runTest {
        val gate = McpCallEvictionGate()
        assertEquals(true, gate.claimEviction("stdio"))
        var oldCalls = 0
        var freshCalls = 0
        val oldClient = McpClient("stdio", BlockingCallTransport {
            oldCalls++
            successfulCallResponse()
        })
        val freshClient = McpClient("stdio", BlockingCallTransport {
            freshCalls++
            successfulCallResponse()
        })
        val tool = McpTool(
            oldClient,
            McpToolDescriptor("after_eviction"),
            onReconnect = {
                oldClient.close()
                gate.finishEviction("stdio")
                freshClient
            },
            onExecutionStateChanged = { active ->
                if (active) gate.beginCall("stdio") else { gate.endCall("stdio"); true }
            },
        )

        tool.execute(emptyMap())

        assertEquals(0, oldCalls)
        assertEquals(1, freshCalls)
        assertEquals(false, gate.hasActiveCalls("stdio"))
    }

    @Test
    fun evictedWorkspaceToolReconnectsWhenSwitchedBack() = runTest {
        var activeWorkspace = "workspace-a"
        var staleCalls = 0
        var freshCalls = 0
        val descriptor = McpToolDescriptor("workspace_tool")
        val staleClient = McpClient("stdio", BlockingCallTransport {
            staleCalls++
            successfulCallResponse()
        }).also { it.tools = listOf(descriptor) }
        staleClient.close()
        val freshClient = McpClient("stdio", BlockingCallTransport {
            freshCalls++
            successfulCallResponse()
        })
        val tool = McpTool(
            staleClient,
            descriptor,
            onReconnect = { if (activeWorkspace == "workspace-a") freshClient else null },
            reconnectClosedClient = { activeWorkspace == "workspace-a" },
        )

        activeWorkspace = "workspace-b"
        activeWorkspace = "workspace-a"
        tool.execute(emptyMap())

        assertEquals(listOf(descriptor), staleClient.tools)
        assertEquals(0, staleCalls)
        assertEquals(1, freshCalls)
    }

    private class BlockingCallTransport(
        private val call: suspend () -> com.aharou.feature.agent.domain.mcp.JsonRpcResponse,
    ) : McpTransport {
        override suspend fun request(method: String, params: JsonObject?): JsonRpcResponse = call()
        override suspend fun notify(method: String, params: JsonObject?) = Unit
        override fun close() = Unit
    }

    private fun successfulCallResponse(): JsonRpcResponse = JsonRpcResponse(
        result = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "done")
                })
            }
        },
    )
}
