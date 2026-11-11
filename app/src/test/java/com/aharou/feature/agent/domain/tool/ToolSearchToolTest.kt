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
        registry.bindDiscoveryTools(registry.getAvailableTools(), context)
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
    @Test fun ordinaryToolDiscoveryEnablesTheNextRequestInTheSameTurnOnly() = runTest {
        val ordinary = object : AgentTool() {
            override val name = "project_tasks"
            override val description = "project task management"
            override val parameters = emptyMap<String, ToolParameter>()
        }
        registry.register(ordinary.name, ordinary)
        val allowed = registry.getAvailableTools()
        registry.bindDiscoveryTools(allowed, context)
        assertFalse(registry.requestTools(allowed, context).contains(ordinary))
        val result = tool.executeWithContext(mapOf("query" to JsonPrimitive("project_tasks")), context)
        assertTrue((result as ToolResult.Success).data.toString().contains("project_tasks"))
        assertTrue(registry.requestTools(allowed, context).contains(ordinary))
        assertFalse(registry.requestTools(allowed, context.copy(sessionId = "b")).contains(ordinary))
    }

    @Test fun searchCannotDiscoverOrActivateToolsOutsideTheAllowedSnapshot() = runTest {
        registry.bindDiscoveryTools(emptyList(), context)
        tool.executeWithContext(mapOf("query" to JsonPrimitive("code_structure")), context)
        assertFalse(registry.isActivated("code_structure", context))
    }

    @Test fun emptyQueryListsPagesWithoutActivatingAnySchema() = runTest {
        val allowed = (0..11).map { index -> object : AgentTool() {
            override val name = "tool-$index"
            override val description = "tool description $index"
            override val parameters = emptyMap<String, ToolParameter>()
        } }
        registry.bindDiscoveryTools(allowed, context)
        val first = tool.executeWithContext(emptyMap(), context) as ToolResult.Success
        assertTrue(first.data.toString().contains("offset=10"))
        assertTrue(allowed.none { registry.isActivated(it.name, context) })
        val next = tool.executeWithContext(mapOf("offset" to JsonPrimitive(10)), context) as ToolResult.Success
        assertTrue(next.data.toString().contains("tool-11"))
        assertFalse(next.data.toString().contains("tool-0:"))
        assertTrue(allowed.none { registry.isActivated(it.name, context) })
        assertEquals("INVALID_ARGS", (tool.executeWithContext(mapOf("offset" to JsonPrimitive(-1)), context) as ToolResult.Error).code)
    }

}
