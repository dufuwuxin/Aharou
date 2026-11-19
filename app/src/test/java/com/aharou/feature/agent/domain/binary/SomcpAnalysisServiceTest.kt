// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.agent.domain.binary

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.file.ElfAnalysisTool
import com.aharou.feature.workspace.domain.FileAccessProvider
import io.mockk.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SomcpAnalysisServiceTest {
    private fun elf(): ByteArray = ByteArray(64).also {
        it[0] = 0x7f; it[1] = 69; it[2] = 76; it[3] = 70; it[4] = 2; it[5] = 1; it[6] = 1
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(16, 3); putShort(18, 183); putInt(20, 1); putShort(52, 64) }
    }

    @Test fun serviceUsesTheCallingWorkspaceAndOnlyBoundedReadOperations() = runBlocking {
        val root = mockk<FileAccessProvider>(); val scoped = mockk<FileAccessProvider>()
        every { root.forWorkspace("/A") } returns scoped
        every { scoped.checked() } returns scoped
        every { scoped.isFile("lib.so") } returns true
        every { scoped.fileSize("lib.so") } returns 64
        every { scoped.readBytesBounded("lib.so", SomcpAnalyzer.MAX_BYTES) } returns elf()
        val report = SomcpAnalysisService(root).analyzePath("/A", "lib.so")
        assertEquals(64, report["bits"]!!.jsonPrimitive.int)
        verify(exactly = 1) { root.forWorkspace("/A") }
        verify(exactly = 1) { scoped.readBytesBounded("lib.so", SomcpAnalyzer.MAX_BYTES) }
        verify(exactly = 0) { scoped.readBytes(any()) }
        verify(exactly = 0) { scoped.writeBytes(any(), any(), any()) }
    }
    @Test fun nativeToolCallsTheInProcessServiceWithoutNetworkOrProcessCapabilities() = runBlocking {
        val service = mockk<SomcpAnalysisService>()
        val result = buildJsonObject { put("read_only", true); put("engine", "Aharou-ELF") }
        coEvery { service.analyzePath("/bound", "a.so", "overview", 0, 50, "") } returns result
        val tool = ElfAnalysisTool(service)
        assertEquals("elf_analyze", tool.name)
        assertEquals(setOf(ToolCapability.READ_WORKSPACE), tool.capabilities)
        assertTrue(tool.deferredLoading)
        assertEquals(ToolResult.Success(result), tool.executeWithContext(mapOf("path" to JsonPrimitive("a.so")), AgentContext(currentFile = null, selectedCode = null, projectRoot = "/bound", language = null)))
        coVerify(exactly = 1) { service.analyzePath("/bound", "a.so", "overview", 0, 50, "") }
    }
    @Test fun malformedRequestsAndDeepBackendsDoNotReachTheReader() = runBlocking {
        val service = mockk<SomcpAnalysisService>(); val tool = ElfAnalysisTool(service)
        for (args in listOf(emptyMap(), mapOf("path" to JsonPrimitive("a.so"), "action" to JsonPrimitive("decompile")),
            mapOf("path" to JsonPrimitive("a.so"), "limit" to JsonPrimitive(0))))
            assertTrue(tool.executeWithContext(args, AgentContext(currentFile = null, selectedCode = null, projectRoot = "/bound", language = null)) is ToolResult.Error)
        coVerify(exactly = 0) { service.analyzePath(any(), any(), any(), any(), any(), any()) }
    }
    @Test fun cancellationIsPropagatedInsteadOfReportedAsAnAnalysisFailure() = runBlocking {
        val service = mockk<SomcpAnalysisService>()
        coEvery { service.analyzePath(any(), any(), any(), any(), any(), any()) } throws CancellationException("cancelled")
        try { ElfAnalysisTool(service).executeWithContext(mapOf("path" to JsonPrimitive("a.so")), AgentContext(currentFile = null, selectedCode = null, projectRoot = "/bound", language = null)); fail("cancel swallowed") }
        catch (_: CancellationException) { }
    }
}
