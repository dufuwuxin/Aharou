package com.aharou.feature.plugin.runtime

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class HostPluginTool(
    override val name: String,
    metadata: JsonObject,
    private val invoke: suspend (JsonObject, AgentContext) -> String,
) : AgentTool() {
    override val description = metadata["description"]?.jsonPrimitive?.content.orEmpty()
    private val schema = (metadata["inputSchema"] ?: metadata["parameters"]) as? JsonObject
        ?: error("Plugin tool must declare inputSchema or parameters")
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXTERNAL_TOOL)
    override val parameters: Map<String, ToolParameter> = run {
        require(schema["type"]?.jsonPrimitive?.content == "object") { "Tool schema must have object type" }
        val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
        (schema["properties"] as? JsonObject).orEmpty().mapValues { (key, value) ->
            val spec = value.jsonObject
            ToolParameter(key, when (spec["type"]?.jsonPrimitive?.content) {
                "boolean" -> ParameterType.BOOLEAN
                "integer", "number" -> ParameterType.INTEGER
                "array" -> ParameterType.ARRAY
                "object" -> ParameterType.OBJECT
                else -> ParameterType.STRING
            }, spec["description"]?.jsonPrimitive?.content.orEmpty(), key in required)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun toJsonSchema(): Map<String, Any> = schema.mapValues { convert(it.value) } as Map<String, Any>

    private fun convert(value: JsonElement): Any? = when (value) {
        is JsonObject -> value.mapValues { convert(it.value) }
        is JsonArray -> value.map { convert(it) }
        JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else
            value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
    }

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult =
        withContext(Dispatchers.IO) {
            try { ToolResult.Success(Json.parseToJsonElement(invoke(JsonObject(args), context))) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { ToolResult.Error("Plugin execution failed: ${failure.javaClass.simpleName}", "HOST_PLUGIN_ERROR") }
        }
}
