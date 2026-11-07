package com.aharou.feature.agent.domain.tool.explorer

import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CodeStructureToolTest {
    private val engine = mockk<CommandEngine>()
    private val tool = CodeStructureTool(engine)
    private val context = AgentContext(null, null, "/workspace", null, sessionId = "session")

    @Test fun queryUsesTheSessionWorkspaceAndBoundedOutput() = runTest {
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } returns CommandResult("{\"partial\":true}", 0)
        val result = tool.executeWithContext(mapOf("action" to JsonPrimitive("map")), context)
        assertTrue(result is ToolResult.Success)
        assertEquals(setOf(ToolCapability.READ_WORKSPACE), tool.capabilities)
        assertTrue(tool.deferredLoading)
        assertFalse(tool.parameters.getValue("path").required)
        coVerify { engine.runCommandSyncIfReady(match { it.contains("python3 -I") && it.endsWith("--limit 80") }, "/workspace", 15_000L) }
    }

    @Test fun checkReturnsDiagnosticJsonWithoutPretendingToCompile() = runTest {
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } returns
            CommandResult("{\"analysis\":\"lexical-not-compiler\",\"valid\":false,\"errors\":[{\"line\":1}]}", 0)
        val result = tool.executeWithContext(mapOf("action" to JsonPrimitive("check")), context)
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).data.toString().contains("lexical-not-compiler"))
        coVerify { engine.runCommandSyncIfReady(match { it.contains("query.py\" check --path") }, "/workspace", 15_000L) }
    }

    @Test fun invalidActionsAndMissingNamesNeverExecute() = runTest {
        assertTrue(tool.executeWithContext(mapOf("action" to JsonPrimitive("install")), context) is ToolResult.Error)
        assertTrue(tool.executeWithContext(mapOf("action" to JsonPrimitive("callers")), context) is ToolResult.Error)
        coVerify(exactly = 0) { engine.runCommandSyncIfReady(any(), any(), any()) }
    }

    @Test fun shellPayloadIsOnlyQuotedData() = runTest {
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } returns CommandResult("{}", 0)
        tool.executeWithContext(mapOf("action" to JsonPrimitive("symbols"), "path" to JsonPrimitive("a'; echo attack; #")), context)
        coVerify { engine.runCommandSyncIfReady(match { it.contains("--path 'a'\"'\"'; echo attack; #'") }, any(), any()) }
    }

    @Test fun unavailableOrTruncatedResultsCannotPretendSuccess() = runTest {
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } returns null
        assertTrue(tool.executeWithContext(mapOf("action" to JsonPrimitive("status")), context) is ToolResult.Error)
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } returns CommandResult("{}", 0, true)
        assertTrue(tool.executeWithContext(mapOf("action" to JsonPrimitive("map")), context) is ToolResult.Error)
    }

    @Test fun backendReturningNullAfterCancellationStillCancelsCaller() = runTest {
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } coAnswers {
            currentCoroutineContext().cancel()
            null
        }
        var returnedNormally = false
        val pending = async {
            tool.executeWithContext(mapOf("action" to JsonPrimitive("map")), context)
            returnedNormally = true
        }
        try {
            pending.await()
            fail("cancelled backend must not become an unavailable result")
        } catch (_: CancellationException) {
            assertTrue(pending.isCancelled)
            assertFalse(returnedNormally)
        }
    }

    @Test fun cancellationPropagates() = runTest {
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } throws CancellationException("fixture")
        try {
            tool.executeWithContext(mapOf("action" to JsonPrimitive("map")), context)
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            // The caller owns cancellation.
        }
    }
}
