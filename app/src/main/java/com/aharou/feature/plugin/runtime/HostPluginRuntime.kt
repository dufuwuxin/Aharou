package com.aharou.feature.plugin.runtime

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.plugin.domain.model.PluginManifest
import com.aharou.plugin.api.AharouPlugin
import dagger.Lazy
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Singleton
class HostPluginRuntime @Inject constructor(
    private val loader: HostPluginLoader,
    private val bridge: Lazy<HostPluginBridge>,
    private val registry: Lazy<ToolRegistry>,
) {
    private class Generation(val plugin: AharouPlugin, val serial: Long) {
        val calls = Mutex()
        @Volatile var context: AgentContext? = null
        @Volatile var active = true
        var tools: List<HostPluginTool> = emptyList()
    }
    private val mutations = Mutex()
    private val sequence = AtomicLong()
    private val loaded = ConcurrentHashMap<String, Generation>()
    private val diagnostics = ConcurrentHashMap<String, String>()
    private val retiring = ConcurrentHashMap<Long, String>()
    // Singleton-owned application lifetime: revocation never waits for a callback that requested it.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _generations = MutableStateFlow<Map<String, Long>>(emptyMap())
    val generations = _generations.asStateFlow()

    fun validate(manifest: PluginManifest, root: File) = loader.validate(root, requireNotNull(manifest.hostRuntime))
    fun isLoaded(id: String): Boolean = loaded[id]?.active == true
    fun diagnostic(id: String): String = diagnostics[id].orEmpty().ifBlank {
        if (retiring.containsValue(id)) "retired generation entrypoints revoked; in-flight cleanup pending" else ""
    }
    fun toolName(id: String, name: String): String {
        val prefix = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
        return "plugin_${prefix}_$name"
    }

    suspend fun load(manifest: PluginManifest, root: File) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val candidate = Generation(loader.load(root, requireNotNull(manifest.hostRuntime)), sequence.incrementAndGet())
            try {
                candidate.plugin.onLoad(bridge.get().createHost(root) { candidate.context })
                val metadata = Json.parseToJsonElement(candidate.plugin.toolsJson()) as? JsonArray
                    ?: error("toolsJson must return a JSON array")
                candidate.tools = metadata.map { item ->
                    val entry = item.jsonObject
                    val localName = entry.getValue("name").jsonPrimitive.content
                    require(localName.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,39}"))) { "Invalid plugin tool name" }
                    HostPluginTool(toolName(manifest.id, localName), entry) { args, context ->
                        invoke(candidate, context) {
                            candidate.plugin.executeTool(localName, args.toString(), contextJson(context))
                        }
                    }
                }
                require(candidate.tools.map { it.name }.distinct().size == candidate.tools.size) { "Duplicate plugin tool" }
                val previous = loaded[manifest.id]
                val owned = previous?.tools.orEmpty().map { it.name }.toSet()
                candidate.tools.forEach { require(!registry.get().hasTool(it.name) || it.name in owned) { "Tool name collision" } }
                previous?.let { retire(manifest.id, it) }
                loaded[manifest.id] = candidate
                candidate.tools.forEach { registry.get().register(it.name, it) }
                _generations.update { it + (manifest.id to candidate.serial) }
                diagnostics.remove(manifest.id)
                Unit
            } catch (cancelled: CancellationException) {
                closeCandidate(candidate)
                throw cancelled
            } catch (failure: Throwable) {
                closeCandidate(candidate)
                diagnostics[manifest.id] = "load: ${failure.javaClass.simpleName}"
                throw failure
            }
        }
    }

    suspend fun unload(id: String) = withContext(Dispatchers.IO) {
        mutations.withLock {
            loaded.remove(id)?.let { retire(id, it) }
            Unit
        }
    }

    private fun retire(id: String, generation: Generation) {
        generation.active = false
        generation.tools.forEach { registry.get().unregister(it.name) }
        _generations.update { if (it[id] == generation.serial) it - id else it }
        retiring[generation.serial] = id
        cleanupScope.launch {
            try {
                generation.calls.withLock { generation.plugin.onUnload() }
            } catch (cancelled: CancellationException) {
                diagnostics.putIfAbsent(id, "retired cleanup cancelled: ${cancelled.javaClass.simpleName}")
                throw cancelled
            } catch (failure: Throwable) { diagnostics.putIfAbsent(id, "retired cleanup: ${failure.javaClass.simpleName}") }
            finally { retiring.remove(generation.serial) }
        }
    }

    private fun closeCandidate(generation: Generation) {
        try { generation.plugin.onUnload() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { /* A failed unpublished candidate owns no public entrypoints. */ }
    }

    private suspend fun <T> invoke(generation: Generation, context: AgentContext?, block: suspend () -> T): T =
        generation.calls.withLock {
            check(generation.active) { "Plugin generation has been unloaded" }
            generation.context = context
            try { block() } finally { generation.context = null }
        }

    private fun contextJson(context: AgentContext) = buildJsonObject {
        put("projectRoot", context.projectRoot)
        put("sessionId", context.sessionId)
        put("inputMessageId", context.inputMessageId)
        put("mode", context.mode.name)
        put("currentFile", context.currentFile)
    }.toString()

    // OkHttp hooks are synchronous, but only their worker waits; UI callers use suspend createView.
    fun onModelRequest(json: String): String = runBlocking(Dispatchers.IO) { transform(json, true) }
    fun onModelEvent(json: String): String = runBlocking(Dispatchers.IO) { transform(json, false) }

    private suspend fun transform(original: String, request: Boolean): String {
        var current = original
        loaded.entries.sortedBy { it.key }.forEach { (id, generation) ->
            try {
                val next = invoke(generation, null) {
                    if (request) generation.plugin.onModelRequest(current) else generation.plugin.onModelEvent(current)
                }
                require(Json.parseToJsonElement(next) is JsonObject) { "Model hook must return a JSON object" }
                current = next
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { diagnostics[id] = "${if (request) "request" else "event"}: ${failure.javaClass.simpleName}" }
        }
        return current
    }

    suspend fun createView(id: String, context: Context): View? {
        val generation = loaded[id] ?: error("Plugin is not loaded")
        return invoke(generation, null) {
            withContext(Dispatchers.Main.immediate) {
                check(generation.active && loaded[id] === generation) { "Plugin generation changed before creating View" }
                val result = generation.plugin.createView(context)
                require(result == null || result is View) { "createView must return android.view.View or null" }
                if (!generation.active || loaded[id] !== generation) {
                    (result as? View)?.let { (it.parent as? ViewGroup)?.removeView(it) }
                    error("Plugin generation changed while creating View")
                }
                result as? View
            }
        }
    }
}
