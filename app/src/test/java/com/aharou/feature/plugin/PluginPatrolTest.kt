package com.aharou.feature.plugin

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.plugin.domain.model.HostRuntimeSpec
import com.aharou.feature.plugin.domain.model.PluginManifest
import com.aharou.feature.plugin.runtime.HostPluginBridge
import com.aharou.feature.plugin.runtime.HostPluginHttpInterceptor
import com.aharou.feature.plugin.runtime.HostPluginLoader
import com.aharou.feature.plugin.runtime.HostPluginRuntime
import com.aharou.plugin.api.PluginHost
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginPatrolTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realJavascriptReachesToolHttpSseConsumersAndUnloadRevokesOnlyItsTools() = runBlocking {
        val engineOrigin = com.dokar.quickjs.QuickJs::class.java.protectionDomain.codeSource.location.toString()
        assertTrue("Patrol must load the host-native JVM engine: $engineOrigin", engineOrigin.contains("quickjs-jvm"))
        val root = temporary.newFolder("plugin")
        File(root, "payload").mkdirs()
        File(root, "payload/plugin.js").writeText("""
            var host;
            var plugin = {
                onLoad: function(h) { host = h; host.call('log', JSON.stringify({phase:'loaded'})); },
                toolsJson: function() { return JSON.stringify([{name:'probe', description:'Patrol fixture',
                    parameters:{type:'object',properties:{value:{type:'string'}},required:['value']}}]); },
                executeTool: function(name,args,context) {
                    if (name !== 'probe') throw new Error('wrong tool');
                    return JSON.stringify({value:JSON.parse(args).value,api:host.apiVersion});
                },
                onModelRequest: function(body) { var value=JSON.parse(body); value.patrol='request'; return JSON.stringify(value); },
                onModelEvent: function(body) { var value=JSON.parse(body); value.patrol='event'; return JSON.stringify(value); },
                onUnload: function() { host.call('log', JSON.stringify({phase:'unloaded'})); }
            };
        """.trimIndent())
        val unloaded = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val host = object : PluginHost {
            override fun apiVersion() = 1
            override fun applicationContext(): Any? = null
            override fun pluginDirectory() = root.path
            override fun dataDirectory() = File(root, "data").path
            override fun call(operation: String, argumentsJson: String): String {
                check(operation == "log")
                val phase = Json.parseToJsonElement(argumentsJson).jsonObject.getValue("phase").jsonPrimitive.content
                events += phase
                if (phase == "unloaded") unloaded.complete(Unit)
                return "null"
            }
        }
        val bridge = mockk<HostPluginBridge>()
        every { bridge.createHost(any(), any()) } returns host
        val registry = ToolRegistry()
        val nativeTool = mockk<com.aharou.feature.agent.domain.tool.AgentTool>(relaxed = true)
        registry.register("patrol_native", nativeTool)
        val runtime = HostPluginRuntime(HostPluginLoader(), Lazy { bridge }, Lazy { registry })
        val manifest = PluginManifest(id = "release.patrol", name = "Controlled patrol",
            hostRuntime = HostRuntimeSpec("js", "plugin.js"))
        runtime.load(manifest, root)
        try {
            assertTrue(runtime.isLoaded(manifest.id))
            assertEquals(listOf("loaded"), events)
            val name = runtime.toolName(manifest.id, "probe")
            val result = registry.getTool(name)!!.executeWithContext(mapOf("value" to JsonPrimitive("actual-tool-value")),
                AgentContext(null, null, root.path, null))
            assertTrue(result is ToolResult.Success)
            assertEquals(Json.parseToJsonElement("{\"value\":\"actual-tool-value\",\"api\":1}"), (result as ToolResult.Success).data)
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"answer\":1}"))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody(": keepalive\n\ndata: {\"delta\":\"real-event\"}\n\ndata: [DONE]\n\n"))
                server.enqueue(MockResponse().setBody("login untouched"))
                val client = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                    .addInterceptor(HostPluginHttpInterceptor(runtime::onModelRequest, runtime::onModelEvent)).build()
                try {
                    val json = "application/json".toMediaType()
                    val body = "{\"model\":\"m\",\"messages\":[]}"
                    fun request(path: String) = Request.Builder().url(server.url(path)).post(body.toRequestBody(json)).build()
                    client.newCall(request("/v1/chat/completions")).execute().use {
                        assertTrue(it.body!!.string().contains("event"))
                    }
                    assertTrue(server.takeRequest().body.readUtf8().contains("request"))
                    client.newCall(request("/v1/chat/completions")).execute().use {
                        val text = it.body!!.string()
                        assertTrue(text.startsWith(": keepalive"))
                        assertTrue(text.contains("\"patrol\":\"event\""))
                        assertEquals(1, Regex("\\[DONE]").findAll(text).count())
                        assertTrue(text.endsWith("data: [DONE]\n\n"))
                    }
                    server.takeRequest()
                    client.newCall(request("/login")).execute().close()
                    assertEquals(body, server.takeRequest().body.readUtf8())
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
            runtime.unload(manifest.id)
            withTimeout(5_000) { unloaded.await() }
            assertFalse(registry.hasTool(name))
            assertSame(nativeTool, registry.getTool("patrol_native"))
            assertFalse(runtime.isLoaded(manifest.id))
            assertEquals("{\"messages\":[]}", runtime.onModelRequest("{\"messages\":[]}"))
        } finally { runtime.unload(manifest.id) }
    }

    @Test fun malformedJavascriptCannotReportAHealthyLoadedPlugin() = runBlocking {
        val root = temporary.newFolder("bad-plugin")
        File(root, "payload").mkdirs()
        File(root, "payload/plugin.js").writeText("var plugin = { onLoad: function() { throw new Error('patrol startup failure'); } };")
        val bridge = mockk<HostPluginBridge>(relaxed = true)
        val registry = ToolRegistry()
        val runtime = HostPluginRuntime(HostPluginLoader(), Lazy { bridge }, Lazy { registry })
        val manifest = PluginManifest(id = "release.bad", name = "Bad patrol", hostRuntime = HostRuntimeSpec("js", "plugin.js"))
        assertTrue(runCatching { runtime.load(manifest, root) }.isFailure)
        assertFalse(runtime.isLoaded(manifest.id))
        assertTrue(registry.getToolNames().isEmpty())
    }
}
