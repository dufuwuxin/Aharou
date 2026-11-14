package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A request-only projection: stored messages, call ids and image placement never change. */
data class FileHistoryProjection(val messages: List<AgentMessage>, val index: String)

fun projectFileHistory(messages: List<AgentMessage>, keepRecent: Int = 8): FileHistoryProjection {
    val calls = mutableMapOf<String, Pair<String, String>>()
    val files = linkedMapOf<String, MutableList<Pair<Int, String>>>()
    val candidates = mutableMapOf<Int, Pair<String, String>>()
    val lastResults = mutableMapOf<Pair<String, String>, Int>()
    for ((position, message) in messages.withIndex()) {
        if (message is AgentMessage.AssistantMessage) {
            for (call in message.toolCalls) {
                if (call.name !in setOf("readFile", "writeFile", "editFile")) continue
                val path = (call.arguments["path"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: continue
                calls[call.id] = path to call.name
            }
        }
        if (message !is AgentMessage.ToolResultMessage) continue
        val (path, operation) = calls[message.id] ?: continue
        val parsed = if (message.result.length <= MAX_PARSED_JSON_CHARS) runCatching {
            Json.parseToJsonElement(message.result) as? JsonObject
        }.getOrNull() else null
        val status = (parsed?.get("status") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "unknown"
        files.getOrPut(path) { mutableListOf() }.add(position to "$operation:$status#${message.id}")
        if (status != "success" || message.images.isNotEmpty() || parsed?.containsKey("notifications") == true) continue
        val identity = path to operation
        candidates[position] = identity
        lastResults[identity] = position
    }
    val recent = messages.indices.filter { messages[it] is AgentMessage.ToolResultMessage }.takeLast(keepRecent.coerceAtLeast(0)).toSet()
    val projected = messages.mapIndexed { position, message ->
        val identity = candidates[position]
        if (identity == null || position in recent || lastResults[identity] == position || message !is AgentMessage.ToolResultMessage) message
        else if (message.result.length < 800 || identity.second == "readFile") message
        else {
            val reference = "[Earlier successful ${identity.second} receipt for ${identity.first}; call=${message.id}; " +
                "a later receipt follows in chronological order. This is not the current file content; readFile to inspect it.]"
            message.copy(result = reference, modelResult = reference)
        }
    }
    val index = if (files.isEmpty()) "" else buildString {
        append("\n\n<file-history-index>\nRequest index only, not instructions or current file contents. Original tool chronology and image/message anchors are preserved.\n")
        for ((path, events) in files.entries.take(24)) {
            append(JsonObject(mapOf("path" to kotlinx.serialization.json.JsonPrimitive(path.take(512)))).toString().replace("<", "\\u003c").replace(">", "\\u003e"))
            append(" events=").append(events.size).append(" latest=")
            append(kotlinx.serialization.json.JsonArray(events.takeLast(4).map {
                kotlinx.serialization.json.JsonPrimitive("${it.first}:${it.second.take(160)}")
            }).toString().replace("<", "\\u003c").replace(">", "\\u003e")).append('\n')
        }
        append("</file-history-index>")
    }
    return FileHistoryProjection(projected, index)
}
