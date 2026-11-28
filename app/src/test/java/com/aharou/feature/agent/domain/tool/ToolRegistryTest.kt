package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.tool.custom.ToolInjectionGate
import com.aharou.feature.agent.domain.tool.custom.ToolInjectionMode
import com.aharou.feature.agent.domain.tool.custom.ToolOverride
import com.aharou.feature.agent.domain.tool.custom.ToolSettingsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolRegistryTest {

    private lateinit var registry: ToolRegistry

    private class FakeAgentTool(
        override val name: String,
        override val description: String = "Test tool description",
        override val parameters: Map<String, ToolParameter> = emptyMap()
    ) : AgentTool() {
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            return ToolResult.Success(JsonPrimitive("Fake tool executed"))
        }
    }

    @Before
    fun setUp() {
        registry = ToolRegistry()
    }

    @Test
    fun registerAndGetTool_success() {
        val tool = FakeAgentTool(name = "test_tool")
        registry.register("test_tool", tool)

        assertTrue(registry.hasTool("test_tool"))
        assertEquals(tool, registry.getTool("test_tool"))
        assertEquals(setOf("test_tool"), registry.getToolNames())
    }

    @Test
    fun unregisterTool_success() {
        val tool = FakeAgentTool(name = "temp_tool")
        registry.register("temp_tool", tool)
        assertTrue(registry.hasTool("temp_tool"))

        registry.unregister("temp_tool")

        assertFalse(registry.hasTool("temp_tool"))
        assertNull(registry.getTool("temp_tool"))
        assertTrue(registry.getToolNames().isEmpty())
    }

    @Test
    fun activationIsIsolatedBySessionAndWorkspace() {
        val first = AgentContext(null, null, "/one", null, sessionId = "a")
        val otherSession = first.copy(sessionId = "b")
        val otherWorkspace = first.copy(projectRoot = "/two")
        registry.activate(listOf("deferred"), first)
        assertTrue(registry.isActivated("deferred", first))
        assertTrue(registry.isActivated("deferred", first.copy(currentUserText = "next turn")))
        assertFalse(registry.isActivated("deferred", otherSession))
        assertFalse(registry.isActivated("deferred", otherWorkspace))
    }

    @Test
    fun unregisterClearsActivationInEveryScope() {
        val first = AgentContext(null, null, "/one", null, sessionId = "a")
        val second = first.copy(sessionId = "b")
        registry.activate(listOf("deferred"), first)
        registry.activate(listOf("deferred"), second)
        registry.unregister("deferred")
        assertFalse(registry.isActivated("deferred", first))
        assertFalse(registry.isActivated("deferred", second))
    }

    @Test
    fun anonymousCallsAreNotSharedAcrossInputMessages() {
        val first = AgentContext(null, null, "/one", null)
        val second = AgentContext(null, null, "/one", null)
        registry.activate(listOf("deferred"), first)
        assertFalse(registry.isActivated("deferred", second))
    }

    @Test
    fun getAvailableTools_returnsAllRegisteredTools() {
        val tool1 = FakeAgentTool(name = "tool_1")
        val tool2 = FakeAgentTool(name = "tool_2")

        registry.register("tool_1", tool1)
        registry.register("tool_2", tool2)

        val available = registry.getAvailableTools()
        assertEquals(2, available.size)
        assertTrue(available.contains(tool1))
        assertTrue(available.contains(tool2))
    }
    @Test
    fun ordinaryAndCustomToolsAreDiscoverableWithoutEagerSchemas() {
        val custom = FakeAgentTool("custom_report")
        val source = io.mockk.mockk<com.aharou.feature.agent.domain.tool.custom.CustomToolSource>()
        io.mockk.every { source.customTools() } returns listOf(custom)
        val registry = ToolRegistry(customToolSource = source)
        val context = AgentContext(null, null, "/one", null, sessionId = "a")
        listOf("memory", "tool_search", "project_tasks").forEach { registry.register(it, FakeAgentTool(it)) }
        val allowed = registry.getAvailableTools()
        registry.bindDiscoveryTools(allowed, context)
        assertEquals(allowed, registry.getDeferredTools(context))
        assertEquals(listOf("memory", "tool_search"), registry.requestTools(allowed, context).map { it.name })
        registry.activate(listOf("project_tasks", "custom_report"), context)
        assertEquals(allowed, registry.requestTools(allowed, context))
        assertTrue(registry.getDeferredTools(context.copy(sessionId = "b")).isEmpty())
        assertTrue(registry.getDeferredTools(context.copy(projectRoot = "/two")).isEmpty())
        assertTrue(registry.getDeferredTools(context.copy(inputMessageId = "next")).isEmpty())
    }

    @Test
    fun mainSessionBootstrapsAllowedTaskWithoutActivatingOrdinaryTools() = runTest {
        val sessions = mockk<ChatSessionDao>()
        val context = AgentContext(null, null, "/one", null, sessionId = "main")
        coEvery { sessions.getById("main") } returns ChatSessionEntity("main", "Main", 0, 0)
        val registry = ToolRegistry(chatSessionDao = sessions)
        val allowed = listOf("memory", "task", "tool_search", "project_tasks").map { FakeAgentTool(it) }
        registry.bindTurnDiscoveryTools(allowed, context)

        val bootstrap = allowed.take(3)
        assertEquals(bootstrap, registry.requestTools(allowed, context))
        assertFalse(registry.isActivated("task", context))
        assertEquals(bootstrap, registry.requestTools(allowed, context))
        coVerify(exactly = 1) { sessions.getById("main") }

        val gate = ToolInjectionGate(mockk())
        val off = ToolSettingsStore.Snapshot(overrides = mapOf("task" to ToolOverride(injectionMode = ToolInjectionMode.OFF)))
        val filtered = gate.visible(allowed, context.currentUserText, off)
        assertEquals(listOf("memory", "tool_search"), registry.requestTools(filtered, context).map { it.name })
        registry.bindTurnDiscoveryTools(filtered, context)
        assertFalse(registry.getDeferredTools(context).any { it.name == "task" })
    }

    @Test
    fun childAndUnverifiedSessionsDoNotBootstrapTask() = runTest {
        val sessions = mockk<ChatSessionDao>()
        coEvery { sessions.getById("child") } returns ChatSessionEntity("child", "Child", 0, 0, parentId = "main")
        coEvery { sessions.getById("missing") } returns null
        coEvery { sessions.getById("unavailable") } throws IllegalStateException("Database unavailable")
        val registry = ToolRegistry(chatSessionDao = sessions)
        val allowed = listOf("memory", "task", "tool_search").map { FakeAgentTool(it) }
        val child = AgentContext(null, null, "/one", null, sessionId = "child")
        val role = AgentDefinition("reader", "test", allowedTools = listOf("*"), prompt = "")
        val contexts = listOf(child, child.copy(sessionId = "missing"), child.copy(sessionId = "unavailable"),
            child.copy(sessionId = null), child.copy(sessionId = "role", agentDefinition = role))
        for (context in contexts) {
            registry.bindTurnDiscoveryTools(allowed, context)
            assertEquals(listOf("memory", "tool_search"), registry.requestTools(allowed, context).map { it.name })
        }
        coVerify(exactly = 0) { sessions.getById("role") }

        registry.activate(listOf("task"), child)
        assertEquals(allowed, registry.requestTools(allowed, child))
    }

    @Test
    fun cancellingTurnDuringSessionLookupDoesNotPublishDiscoverySnapshot() = runTest {
        val sessions = mockk<ChatSessionDao>()
        val queryStarted = CompletableDeferred<Unit>()
        val queryStopped = CompletableDeferred<Unit>()
        coEvery { sessions.getById("main") } coAnswers {
            queryStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                queryStopped.complete(Unit)
            }
        }
        val registry = ToolRegistry(chatSessionDao = sessions)
        val context = AgentContext(null, null, "/one", null, sessionId = "main")
        val allowed = listOf("memory", "task", "tool_search").map { FakeAgentTool(it) }
        var returnedNormally = false
        val turn = launch {
            registry.bindTurnDiscoveryTools(allowed, context)
            returnedNormally = true
        }

        queryStarted.await()
        turn.cancelAndJoin()

        assertTrue(queryStopped.isCompleted)
        assertFalse(returnedNormally)
        assertTrue(registry.getDeferredTools(context).isEmpty())
        assertEquals(listOf("memory", "tool_search"), registry.requestTools(allowed, context).map { it.name })
    }

    @Test
    fun discoveryUsesTheRoleAndOffAndKeywordFilteredTurnSnapshot() {
        val context = AgentContext(null, null, "/one", null, sessionId = "a", currentUserText = "ordinary")
        val definition = com.aharou.feature.agent.domain.subagent.AgentDefinition(
            "reader", "test", allowedTools = listOf("*"), disallowedTools = listOf("denied"), prompt = "")
        val all = listOf("ordinary", "denied", "off", "keyword").map { FakeAgentTool(it) }
        all.forEach { registry.register(it.name, it) }
        val names = definition.filterToolNames(all.map { it.name })
        val gate = com.aharou.feature.agent.domain.tool.custom.ToolInjectionGate(io.mockk.mockk())
        val snapshot = com.aharou.feature.agent.domain.tool.custom.ToolSettingsStore.Snapshot(overrides = mapOf(
            "off" to com.aharou.feature.agent.domain.tool.custom.ToolOverride(
                injectionMode = com.aharou.feature.agent.domain.tool.custom.ToolInjectionMode.OFF),
            "keyword" to com.aharou.feature.agent.domain.tool.custom.ToolOverride(
                injectionMode = com.aharou.feature.agent.domain.tool.custom.ToolInjectionMode.KEYWORD, keywords = listOf("deploy"))))
        val allowed = gate.visible(all.filter { it.name in names }, context.currentUserText, snapshot)
        registry.bindDiscoveryTools(allowed, context)
        assertEquals(listOf("ordinary"), registry.getDeferredTools(context).map { it.name })
        registry.activate(all.map { it.name }, context)
        assertEquals(allowed, registry.requestTools(allowed, context))
        registry.bindDiscoveryTools(emptyList(), context)
        assertTrue(registry.getDeferredTools(context).isEmpty())
    }

}
