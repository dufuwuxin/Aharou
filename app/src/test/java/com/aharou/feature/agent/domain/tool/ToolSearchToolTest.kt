package com.aharou.feature.agent.domain.tool

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentContext
import dagger.Lazy
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToolSearchToolTest {
    private val registry = ToolRegistry()
    private val tool = ToolSearchTool(Lazy { registry })
    private val context = AgentContext(null, null, "/workspace", null, sessionId = "a")

    @Before fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        registry.register("code_structure", object : AgentTool() {
            override val name = "code_structure"
            override val description = "Kotlin Rust Python symbols repo map"
            override val parameters = emptyMap<String, ToolParameter>()
            override val deferredLoading = true
        })
    }

    @After fun tearDown() { unmockkObject(FileLogger) }

    @Test fun discoveryActivatesOnlyTheCallingSessionWorkspace() = runTest {
        val result = tool.executeWithContext(mapOf("query" to JsonPrimitive("Kotlin symbols")), context)
        assertTrue(result is ToolResult.Success)
        assertTrue(registry.isActivated("code_structure", context))
        assertFalse(registry.isActivated("code_structure", context.copy(sessionId = "b")))
        assertFalse(registry.isActivated("code_structure", context.copy(projectRoot = "/other")))
    }

    @Test fun unmatchedQueriesDoNotActivateAnything() = runTest {
        tool.executeWithContext(mapOf("query" to JsonPrimitive("unmatched")), context)
        assertFalse(registry.isActivated("code_structure", context))
    }
}
