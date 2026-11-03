package com.aharou.feature.agent.domain.tool.script

import android.content.Context
import com.aharou.core.util.writeTextSafely
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.container.ExecuteCommandTool
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScriptToolManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val executor: ExecuteCommandTool,
    private val registry: ToolRegistry,
) {
    private val file get() = File(context.filesDir, "aharou/script-tools.json")
    private val json = Json { encodeDefaults = true }
    private val mutableDefinitions = MutableStateFlow<List<ScriptToolDefinition>>(emptyList())
    val definitions = mutableDefinitions.asStateFlow()
    private val registered = mutableMapOf<String, AgentTool>()

    @Synchronized
    fun reload() {
        // A corrupt file must not leave previously registered scripts executable.
        try {
            val loaded = if (file.exists()) json.decodeFromString<List<ScriptToolDefinition>>(file.readText()) else emptyList()
            publish(loaded, persist = false)
        } catch (e: Exception) {
            unregisterOwned()
            mutableDefinitions.value = emptyList()
            throw e
        }
    }

    @Synchronized
    fun add(definition: ScriptToolDefinition) {
        require(definitions.value.none { it.name == definition.name }) { "Duplicate script name" }
        publish(definitions.value + definition)
    }

    @Synchronized
    fun update(name: String, definition: ScriptToolDefinition) {
        require(definitions.value.any { it.name == name }) { "Script not found" }
        require(definitions.value.none { it.name != name && it.name == definition.name }) { "Duplicate script name" }
        publish(definitions.value.map { if (it.name == name) definition else it })
    }

    @Synchronized
    fun setEnabled(name: String, enabled: Boolean) {
        val current = requireNotNull(definitions.value.firstOrNull { it.name == name }) { "Script not found" }
        update(name, current.copy(enabled = enabled))
    }

    @Synchronized
    fun remove(name: String) {
        require(definitions.value.any { it.name == name }) { "Script not found" }
        publish(definitions.value.filterNot { it.name == name })
    }

    @Synchronized
    private fun isCurrent(definition: ScriptToolDefinition): Boolean = definitions.value.any {
        it.name == definition.name && it.enabled && it.toolName == definition.toolName
    }

    private fun unregisterOwned() {
        registered.forEach { (name, instance) ->
            if (registry.getTool(name) === instance) registry.unregister(name)
        }
        registered.clear()
    }

    private fun publish(next: List<ScriptToolDefinition>, persist: Boolean = true) {
        ScriptToolDefinition.validateAll(next)
        val enabled = next.filter { it.enabled }
        enabled.forEach {
            val existing = registry.getTool(it.toolName)
            require(existing == null || existing === registered[it.toolName]) { "Tool namespace conflict" }
        }
        if (persist) {
            require(file.writeTextSafely(json.encodeToString(next), "ScriptToolManager")) { "Could not save scripts" }
        }
        unregisterOwned()
        mutableDefinitions.value = next.toList()
        enabled.forEach {
            registry.register(it.toolName, ScriptTool(it))
            registered[it.toolName] = requireNotNull(registry.getTool(it.toolName))
        }
    }

    private inner class ScriptTool(private val definition: ScriptToolDefinition) : AgentTool() {
        override val name = definition.toolName
        override val description = definition.description
        override val permissionPolicy = ToolPermissionPolicy.ASK
        override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)
        override val parameters = mapOf(
            "input" to ToolParameter("input", ParameterType.OBJECT, "JSON object delivered to the script on stdin"),
        ) + executor.parameters.filterKeys { it in setOf("timeout", "elevate", "reason") }

        override fun permissionCommand(args: Map<String, JsonElement>): String = definition.command

        override fun buildPermissionRequest(callId: String, args: Map<String, JsonElement>, argsPreview: String) =
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = definition.description,
                summary = definition.command,
                details = "Script: ${definition.command}\nInput: ${args["input"]}",
                argsPreview = argsPreview,
            )

        override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
            if (!isCurrent(definition)) return ToolResult.Error("Script was removed, disabled or changed; request the current tool", "SCRIPT_CHANGED")
            val input = args["input"] as? JsonObject
                ?: return ToolResult.Error("input must be a JSON object", "INVALID_ARGUMENTS")
            val commandArgs = args.filterKeys { it in setOf("timeout", "elevate", "reason") } +
                ("command" to JsonPrimitive(definition.executionCommand(input)))
            return executor.executeWithContext(commandArgs, context)
        }
    }
}
