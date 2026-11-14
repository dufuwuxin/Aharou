package com.aharou.core.translation

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.StreamingAgentTool
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Request-local descriptors; the registry and its executable tools are never replaced. */
internal suspend fun projectLanguageTools(
    tools: List<AgentTool>,
    target: String,
    prose: suspend (String, String) -> String,
): List<AgentTool> {
    val descriptions = mutableMapOf<String, String>()
    suspend fun translate(text: String): String = descriptions[text]
        ?: prose(text, target).also { descriptions[text] = it }
    val projection = ToolSchemaLanguageProjection(::translate)
    return tools.map { tool ->
        val description = translate(tool.description)
        val parameters = tool.parameters.mapValues { (_, parameter) ->
            parameter.copy(
                description = translate(parameter.description),
                itemsSchema = parameter.itemsSchema?.let { projection.schema(it) },
            )
        }
        // MCP/custom tools can expose schemas that parameters cannot represent.
        val schema = projection.schema(tool.toJsonSchema())
        if (tool is StreamingAgentTool) {
            LanguageProjectedStreamingTool(tool, description, parameters, schema)
        } else {
            LanguageProjectedTool(tool, description, parameters, schema)
        }
    }
}

private class ToolSchemaLanguageProjection(private val translate: suspend (String) -> String) {
    private val schemaDictionaries = setOf("properties", "patternProperties", "definitions", "\$defs", "dependentSchemas", "dependencies")
    // These are instance data, not schemas: an object inside a default/enum may have a literal description key.
    private val instanceKeywords = setOf("default", "const", "enum", "examples", "example", "required", "dependentRequired")

    @Suppress("UNCHECKED_CAST")
    suspend fun schema(source: Map<String, Any>): Map<String, Any> = project(source) as Map<String, Any>

    private suspend fun description(value: Any?): Any? = when (value) {
        is String -> translate(value)
        is JsonPrimitive -> if (value.isString) JsonPrimitive(translate(value.content)) else value
        else -> value
    }

    private suspend fun entry(key: Any?, value: Any?, dictionary: Boolean): Any? = when {
        dictionary -> project(value)
        key == "description" -> description(value)
        key in instanceKeywords -> value
        else -> project(value, key in schemaDictionaries)
    }

    private suspend fun project(value: Any?, dictionary: Boolean = false): Any? = when (value) {
        is JsonObject -> {
            val result = linkedMapOf<String, JsonElement>()
            for ((key, child) in value) result[key] = entry(key, child, dictionary) as JsonElement
            JsonObject(result)
        }
        is JsonArray -> {
            val result = mutableListOf<JsonElement>()
            for (child in value) result += project(child) as JsonElement
            JsonArray(result)
        }
        is Map<*, *> -> {
            val result = linkedMapOf<Any?, Any?>()
            for ((key, child) in value) result[key] = entry(key, child, dictionary)
            result
        }
        is List<*> -> {
            val result = mutableListOf<Any?>()
            for (child in value) result += project(child)
            result
        }
        else -> value
    }
}

private open class LanguageProjectedTool(
    protected val delegate: AgentTool,
    override val description: String,
    override val parameters: Map<String, ToolParameter>,
    private val schema: Map<String, Any>,
) : AgentTool() {
    override val name get() = delegate.name
    override val permissionPolicy get() = delegate.permissionPolicy
    override val capabilities get() = delegate.capabilities
    override val acceptsSecrets get() = delegate.acceptsSecrets
    override val deferredLoading get() = delegate.deferredLoading
    override fun toJsonSchema(): Map<String, Any> = schema
    override fun effectivePermissionPolicy(mode: AgentMode) = delegate.effectivePermissionPolicy(mode)
    override fun effectiveCapabilities(args: Map<String, JsonElement>) = delegate.effectiveCapabilities(args)
    override fun isCallSensitive(args: Map<String, JsonElement>) = delegate.isCallSensitive(args)
    override fun permissionCommand(args: Map<String, JsonElement>) = delegate.permissionCommand(args)
    override fun buildPermissionRequest(callId: String, args: Map<String, JsonElement>, argsPreview: String): PendingToolPermission =
        delegate.buildPermissionRequest(callId, args, argsPreview)
    override suspend fun buildPermissionPreview(callId: String, args: Map<String, JsonElement>, context: AgentContext): PendingToolPermission? =
        delegate.buildPermissionPreview(callId, args, context)
    override suspend fun execute(args: Map<String, JsonElement>) = delegate.execute(args)
    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext) = delegate.executeWithContext(args, context)
}

private class LanguageProjectedStreamingTool(
    delegate: AgentTool,
    description: String,
    parameters: Map<String, ToolParameter>,
    schema: Map<String, Any>,
) : LanguageProjectedTool(delegate, description, parameters, schema), StreamingAgentTool {
    override fun executeStream(args: Map<String, JsonElement>, context: AgentContext): Flow<ToolStreamEvent> =
        (delegate as StreamingAgentTool).executeStream(args, context)
}
