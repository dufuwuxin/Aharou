package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.tool.script.ScriptToolDefinition
import com.aharou.feature.agent.domain.tool.script.ScriptToolManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigScriptToolFields @Inject constructor(private val manager: ScriptToolManager) {
    fun registerInto(registry: ConfigRegistry) { registry.register(Scripts()) }

    private fun current(name: String): ScriptToolDefinition = manager.definitions.value.firstOrNull { it.name == name }
        ?: throw ConfigError.InvalidValue("Script not found")

    private fun mutate(name: String, transform: (ScriptToolDefinition) -> ScriptToolDefinition) {
        checked { manager.update(name, transform(current(name))) }
    }

    private fun <T> checked(block: () -> T): T = try { block() } catch (e: IllegalArgumentException) {
        throw ConfigError.InvalidValue(e.message ?: "Invalid script")
    }

    private inner class Scripts : ConfigCollection {
        override val basePath = "script_tools"
        override val displayName = "Script tools"
        override val description = "Container shell scripts. Each script receives input as a JSON object on stdin."
        override val risk = ConfigRisk.SENSITIVE
        override fun childIds() = manager.definitions.value.map { it.name }
        override fun fields(forId: String): List<ConfigField> {
            if (manager.definitions.value.none { it.name == forId }) return emptyList()
            fun text(segment: String, read: (ScriptToolDefinition) -> String, apply: (ScriptToolDefinition, String) -> ScriptToolDefinition) = ClosureField(
                path = "$basePath.$forId.$segment",
                displayName = segment,
                description = "Script $segment",
                valueSchema = ConfigSchema.Str(),
                risk = if (segment == "command") ConfigRisk.SENSITIVE else ConfigRisk.NORMAL,
                reader = { ConfigValue.Str(read(current(forId))) },
                writer = { value ->
                    val text = (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    mutate(forId) { apply(it, text) }
                },
            )
            return listOf(
                text("description", { it.description }, { d, value -> d.copy(description = value) }),
                text("command", { it.command }, { d, value -> d.copy(command = value) }),
                ClosureField(
                    path = "$basePath.$forId.enabled",
                    displayName = "Enabled",
                    description = "Register or unload this script tool",
                    valueSchema = ConfigSchema.Bool,
                    risk = ConfigRisk.SENSITIVE,
                    reader = { ConfigValue.Bool(current(forId).enabled) },
                    writer = { value ->
                        val enabled = (value as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")
                        checked { manager.setEnabled(forId, enabled) }
                    },
                ),
            )
        }

        override fun add(payload: ConfigValue): String = checked {
            val obj = (payload as? ConfigValue.Obj)?.value ?: throw ConfigError.TypeMismatch("object")
            fun text(key: String) = (obj[key] as? ConfigValue.Str)?.value ?: throw ConfigError.InvalidValue("Missing $key")
            val enabled = obj["enabled"]?.let { (it as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean") } ?: true
            val definition = ScriptToolDefinition(text("name"), text("description"), text("command"), enabled)
            manager.add(definition)
            definition.name
        }

        override fun remove(id: String) { checked { manager.remove(id) } }
    }
}
