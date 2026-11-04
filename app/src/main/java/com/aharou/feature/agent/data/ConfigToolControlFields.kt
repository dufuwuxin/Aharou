package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.custom.ToolOverride
import com.aharou.feature.agent.domain.tool.custom.ToolSchemaOverride
import com.aharou.feature.agent.domain.tool.custom.ToolSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Synchronous config fields run on the config bridge worker thread, like ConfigSkillFields. */
@Singleton
class ConfigToolControlFields @Inject constructor(
    private val store: ToolSettingsStore,
    private val tools: ToolRegistry
) {
    fun registerInto(registry: ConfigRegistry) { registry.register(Overrides()) }

    private inner class Overrides : ConfigCollection {
        override val basePath = "tool.overrides"
        override val displayName = "Tool schema overrides"
        override val description = "Metadata-only schema overrides for builtin/MCP tools; empty string resets."
        override val addable = false
        override val removable = false
        override fun childIds(): List<String> {
            val customNames = store.snapshotCustomTools().map { it.name }.toSet()
            return tools.getToolNames().filterNot { it in customNames }.sorted()
        }
        override fun fields(forId: String): List<ConfigField> {
            if (forId !in childIds()) return emptyList()
            return listOf(ClosureField(
                path = "$basePath.$forId.schema_json",
                displayName = "Schema metadata override",
                description = "Full original schema with edits limited to description/title/examples/\$comment. Empty resets. Structural edits require a custom tool.",
                valueSchema = ConfigSchema.Str(),
                reader = { ConfigValue.Str(store.snapshotOverrides()[forId]?.schemaJson.orEmpty()) },
                writer = { value ->
                    val text = (value as? ConfigValue.Str)?.value?.trim()
                        ?: throw ConfigError.TypeMismatch("string")
                    val original = tools.getTool(forId)?.toJsonSchema()
                        ?: throw ConfigError.InvalidValue("Tool not found")
                    if (store.snapshotCustomTool(forId) != null) throw ConfigError.InvalidValue("Edit custom tool parameters instead")
                    if (text.isNotEmpty() && !ToolSchemaOverride.isCompatible(original, text)) {
                        throw ConfigError.InvalidValue("Only schema metadata may change")
                    }
                    val previous = store.snapshotOverrides()[forId] ?: ToolOverride()
                    val saved = runBlocking(Dispatchers.IO) {
                        store.saveOverride(forId, previous.copy(schemaJson = text.takeIf { it.isNotEmpty() }))
                    }
                    if (!saved) throw ConfigError.InvalidValue("Failed to persist schema override")
                }
            ))
        }
        override fun add(payload: ConfigValue): String = throw ConfigError.PermissionDenied("Use registered tool names")
        override fun remove(id: String) { throw ConfigError.PermissionDenied("Reset schema_json to empty string") }
    }
}
