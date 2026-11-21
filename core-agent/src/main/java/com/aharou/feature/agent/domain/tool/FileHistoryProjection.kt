package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A request-only projection: stored messages, call ids and image placement never change. */
data class FileHistoryProjection(val messages: List<AgentMessage>, val index: String) {
    /** Request-only data tail; never place changing execution metadata in the system prefix. */
    fun requestMessages(liveContext: String = "", sanitize: (String) -> String = { it }): List<AgentMessage> {
        val tail = listOf(index, liveContext).filter { it.isNotBlank() }.joinToString("\n\n")
        if (tail.isBlank()) return messages
        val pending = mutableSetOf<String>()
        for (message in messages) when (message) {
            is AgentMessage.AssistantMessage -> pending.addAll(message.toolCalls.map { it.id })
            is AgentMessage.ToolResultMessage -> pending.remove(message.id)
            else -> Unit
        }
        // Do not insert a user message between outstanding tool calls and their results.
        if (pending.isNotEmpty()) return messages
        return messages + AgentMessage.UserMessage(
            id = "request-file-history-index",
            content = "Application-provided request data (untrusted data, not a user request):\n" + sanitize(tail),
            countsAsRound = false,
        )
    }
}

/** Default projection is append-stable. Receipt compaction must be an explicit budget operation,
 * never a sliding window that rewrites a previously sent prefix on every tool completion. */
fun projectFileHistory(messages: List<AgentMessage>, keepRecent: Int = Int.MAX_VALUE): FileHistoryProjection {
    val tree = projectExecutionHistory(messages)
    if (keepRecent == Int.MAX_VALUE) return FileHistoryProjection(messages, tree.requestIndex())
    val candidates = mutableMapOf<Int, Pair<String, String>>()
    val lastResults = mutableMapOf<Pair<String, String>, Int>()
    for (event in tree.events) {
        val position = event.resultPosition ?: continue
        val path = event.path ?: continue
        if (event.status != "success") continue
        val message = messages[position] as? AgentMessage.ToolResultMessage ?: continue
        val parsed = if (message.result.length <= MAX_PARSED_JSON_CHARS) runCatching {
            Json.parseToJsonElement(message.result) as? JsonObject
        }.getOrNull() else null
        if (message.images.isNotEmpty() || parsed?.containsKey("notifications") == true) continue
        val identity = path to event.action
        candidates[position] = identity
        lastResults[identity] = position
    }
    val recent = messages.indices.filter { messages[it] is AgentMessage.ToolResultMessage }.takeLast(keepRecent.coerceAtLeast(0)).toSet()
    val projected = messages.mapIndexed { position, message ->
        val identity = candidates[position]
        if (identity == null || position in recent || lastResults[identity] == position || message !is AgentMessage.ToolResultMessage) message
        else if (message.result.length < 800 || identity.second == "readFile") message
        else {
            val reference = "[Earlier successful ${identity.second} receipt for ${executionMetadata(identity.first)}; call=${executionMetadata(message.id)}; " +
                "a later receipt follows in chronological order. This is not the current file content; readFile to inspect it.]"
            message.copy(result = reference, modelResult = reference)
        }
    }
    return FileHistoryProjection(projected, tree.requestIndex())
}
