package com.aharou.feature.agent.domain.tool.custom

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ToolPromptMetadata {
    fun pluginKey(id: String): String = "plugin_${hash(id).take(16)}_"

    fun pluginKeyForTool(name: String): String? =
        Regex("^plugin_[0-9a-f]{16}_").find(name)?.value

    fun version(snapshot: ToolSettingsStore.Snapshot, key: String, original: String): String =
        hash(original + "\n" + Json.encodeToString(snapshot.overrides[key] ?: ToolOverride()))

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
