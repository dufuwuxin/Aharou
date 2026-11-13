package com.aharou.feature.plugin

import com.aharou.plugin.api.AharouPlugin
import com.aharou.feature.plugin.runtime.HostPluginRuntime
import com.aharou.feature.plugin.runtime.HostPluginLoader
import com.aharou.feature.plugin.runtime.HostPluginBridge
import com.aharou.feature.plugin.domain.model.HostRuntimeSpec
import com.aharou.feature.plugin.domain.model.PluginManifest
import com.aharou.feature.agent.domain.tool.ToolRegistry
import dagger.Lazy
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HostPluginRuntimeTest {
    @get:Rule val temp = TemporaryFolder()
    private val registry = ToolRegistry()
    private val loader = mockk<HostPluginLoader>()
    private val bridge = mockk<HostPluginBridge>(relaxed = true)
    private val runtime = HostPluginRuntime(loader, Lazy { bridge }, Lazy { registry })
    private fun manifest() = PluginManifest(id = "fixture", name = "Fixture",
        hostRuntime = HostRuntimeSpec("dex", "example.Plugin", "classes.dex"))

    @Test fun failedStartupNeverReportsLoaded() = runBlocking {
        every { loader.load(any(), any()) } returns object : AharouPlugin {
            override fun onLoad(host: com.aharou.plugin.api.PluginHost) { error("fixture startup") }
        }
        assertTrue(runCatching { runtime.load(manifest(), temp.root) }.isFailure)
        assertFalse(runtime.isLoaded("fixture"))
        assertTrue(registry.getToolNames().isEmpty())
    }

    @Test fun toolConsumerUsesMetadataAndUnloadRevokesOnlyOwnedTools() = runBlocking {
        every { loader.load(any(), any()) } returns object : AharouPlugin {
            override fun toolsJson() = """[{"name":"echo","description":"Echo fixture","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}]"""
            override fun executeTool(name: String, argumentsJson: String, contextJson: String) = argumentsJson
        }
        runtime.load(manifest(), temp.root)
        val name = runtime.toolName("fixture", "echo")
        assertNotNull(registry.getAvailableTools().firstOrNull { it.name == name })
        assertTrue(registry.getTool(name)!!.toJsonSchema().toString().contains("text"))
        val context = com.aharou.feature.agent.domain.model.AgentContext(null, null, temp.root.path, null)
        val result = registry.getTool(name)!!.executeWithContext(mapOf("text" to kotlinx.serialization.json.JsonPrimitive("real consumer")), context)
        assertTrue(result is com.aharou.feature.agent.domain.tool.ToolResult.Success)
        runtime.unload("fixture")
        assertFalse(registry.hasTool(name))
    }

    @Test fun failingReplacementAndHookKeepOldGeneration() = runBlocking {
        every { loader.load(any(), any()) } returns object : AharouPlugin {
            override fun onModelRequest(requestJson: String): String = error("fixture hook")
        }
        runtime.load(manifest(), temp.root)
        every { loader.load(any(), any()) } throws IllegalStateException("bad replacement")
        assertTrue(runCatching { runtime.load(manifest(), temp.root) }.isFailure)
        assertTrue(runtime.isLoaded("fixture"))
        assertEquals("{\"messages\":[]}", runtime.onModelRequest("{\"messages\":[]}"))
        assertTrue(runtime.isLoaded("fixture"))
    }
    @Test fun nativeJavascriptLifecycleUsesTheRealHostBinding() {
        val host = mockk<com.aharou.plugin.api.PluginHost>()
        every { host.apiVersion() } returns 1
        every { host.pluginDirectory() } returns temp.root.path
        every { host.dataDirectory() } returns File(temp.root, "data").path
        every { host.call("log", any()) } returns "null"
        val plugin = com.aharou.feature.plugin.runtime.JavascriptHostPlugin("""
            var plugin = {
                onLoad: function(host) { host.call('log', '{"message":"loaded"}'); },
                toolsJson: function() { return '[]'; },
                onModelEvent: function(event) { var e = JSON.parse(event); e.fixture = true; return JSON.stringify(e); }
            };
        """.trimIndent())
        try {
            plugin.onLoad(host)
            assertEquals("[]", plugin.toolsJson())
            assertTrue(plugin.onModelEvent("{}").contains("fixture"))
            verify(exactly = 1) { host.call("log", any()) }
        } finally { plugin.onUnload() }
    }

    @Test fun viewWaitSuspendsMainAndRevocationRejectsQueuedViewBeforeCleanup() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-test-main") }.asCoroutineDispatcher()
        Dispatchers.setMain(dispatcher)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CompletableDeferred<Unit>()
        val created = AtomicInteger()
        every { loader.load(any(), any()) } returns object : AharouPlugin {
            override fun toolsJson() = """[{"name":"wait","parameters":{"type":"object","properties":{}}}]"""
            override fun executeTool(name: String, argumentsJson: String, contextJson: String): String {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return "{}"
            }
            override fun createView(androidContext: Any): Any? { created.incrementAndGet(); return null }
            override fun onUnload() { cleaned.complete(Unit) }
        }
        runtime.load(manifest(), temp.root)
        val context = com.aharou.feature.agent.domain.model.AgentContext(null, null, temp.root.path, null)
        val tool = registry.getTool(runtime.toolName("fixture", "wait"))!!
        val call = async(Dispatchers.IO) { tool.executeWithContext(emptyMap(), context) }
        var view: Deferred<Result<android.view.View?>>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            val queued = CompletableDeferred<Unit>()
            val viewJob = async(Dispatchers.Main) {
                queued.complete(Unit)
                runCatching { runtime.createView("fixture", mockk<android.content.Context>()) }
            }
            view = viewJob
            withTimeout(2000) { queued.await() }
            val heartbeat = async(Dispatchers.Main) { true }
            assertTrue(withTimeout(2000) { heartbeat.await() })
            withTimeout(2000) { runtime.unload("fixture") }
            assertFalse(runtime.isLoaded("fixture"))
            assertNull(runtime.generations.value["fixture"])
            assertFalse(registry.hasTool(tool.name))
            assertFalse(cleaned.isCompleted)
            release.countDown()
            withTimeout(2000) { call.await(); cleaned.await() }
            assertTrue(withTimeout(2000) { viewJob.await() }.isFailure)
            assertEquals(0, created.get())
            val stale = tool.executeWithContext(emptyMap(), context)
            assertTrue(stale is com.aharou.feature.agent.domain.tool.ToolResult.Error)
        } finally {
            release.countDown()
            view?.cancelAndJoin()
            call.cancelAndJoin()
            runtime.unload("fixture")
            Dispatchers.resetMain()
            dispatcher.close()
        }
    }

    @Test fun selfReloadPublishesNewGenerationBeforeOldCallbackExits() = runBlocking {
        val cleaned = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "self-reload-fixture").apply { isDaemon = true } }
        lateinit var initialTool: com.aharou.feature.agent.domain.tool.AgentTool
        every { loader.load(any(), any()) } answers {
            val initial = calls.incrementAndGet() == 1
            object : AharouPlugin {
                override fun toolsJson() = """[{"name":"reload","parameters":{"type":"object","properties":{}}}]"""
                override fun executeTool(name: String, argumentsJson: String, contextJson: String): String {
                    val old = runtime.generations.value["fixture"]
                    executor.submit { runBlocking { runtime.load(manifest(), temp.root) } }.get(2, TimeUnit.SECONDS)
                    check(runtime.generations.value["fixture"] != old)
                    check(!cleaned.isCompleted)
                    return "{}"
                }
                override fun onUnload() { if (initial) cleaned.complete(Unit) }
            }
        }
        runtime.load(manifest(), temp.root)
        initialTool = registry.getTool(runtime.toolName("fixture", "reload"))!!
        val context = com.aharou.feature.agent.domain.model.AgentContext(null, null, temp.root.path, null)
        try {
            val result = withTimeout(3000) { initialTool.executeWithContext(emptyMap(), context) }
            assertTrue(result is com.aharou.feature.agent.domain.tool.ToolResult.Success)
            withTimeout(2000) { cleaned.await() }
            assertTrue(runtime.isLoaded("fixture"))
        } finally { runtime.unload("fixture"); executor.shutdownNow() }
    }

}
