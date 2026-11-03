package com.aharou.feature.agent.domain.tool.script

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

@Serializable
data class ScriptToolDefinition(
    val name: String,
    val description: String,
    val command: String,
    val enabled: Boolean = true,
) {
    init {
        require(Regex("[A-Za-z][A-Za-z0-9_]{0,23}").matches(name)) { "Invalid script name" }
        require(description.isNotBlank()) { "Description is required" }
        require(command.isNotBlank() && '\u0000' !in command) { "Invalid command" }
    }

    val toolName: String
        get() {
            val hash = MessageDigest.getInstance("SHA-256").digest(command.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "script_${name}_$hash"
        }

    // Input is shell data, never source: both JSON and the saved body are separate quoted arguments.
    fun executionCommand(input: JsonObject): String =
        "printf '%s' ${shellQuote(input.toString())} | sh -c ${shellQuote(command)}"

    companion object {
        fun validateAll(definitions: List<ScriptToolDefinition>) {
            require(definitions.map { it.name }.distinct().size == definitions.size) { "Duplicate script name" }
            require(definitions.map { it.toolName }.distinct().size == definitions.size) { "Duplicate tool identity" }
        }

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
