package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.AgentTool
import com.google.gson.Gson
import java.security.MessageDigest

/** Bounded, metadata-only diagnostics. Retains hashes, never prompt text, tool arguments or credentials. */
internal class RequestPrefixTracker {
    data class Fingerprint(val system: String, val tools: String, val messages: List<String>)
    data class Change(val systemChanged: Boolean, val toolsChanged: Boolean,
        val unchangedMessages: Int, val previousMessages: Int, val currentMessages: Int)
    private val previous = LinkedHashMap<String, Fingerprint>(16, 0.75f, true)
    private val gson = Gson()

    @Synchronized
    fun record(key: String, system: String, messages: List<AgentMessage>, tools: List<AgentTool>): Change? {
        val next = Fingerprint(hash(system), hash(tools.joinToString("\n") {
            it.name + it.description + gson.toJson(it.toJsonSchema())
        }), messages.map { message ->
            val images = when (message) {
                is AgentMessage.UserMessage -> message.images
                is AgentMessage.AssistantMessage -> message.images
                is AgentMessage.ToolResultMessage -> message.images
            }
            val callIds = when (message) {
                is AgentMessage.AssistantMessage -> message.toolCalls.joinToString { it.id }
                is AgentMessage.ToolResultMessage -> message.id
                else -> ""
            }
            // Local row IDs and transient counters are not provider prompt content. Image data is
            // hashed separately, avoiding a second JSON/base64 copy of a large multimodal request.
            hash(message.javaClass.simpleName + callIds + RequestContextComposition.messageText(message) +
                images.joinToString { it.mimeType + hash(it.base64Data) })
        })
        val old = previous.put(key, next)
        while (previous.size > 128) previous.remove(previous.keys.first())
        if (old == null) return null
        val unchanged = old.messages.zip(next.messages).takeWhile { it.first == it.second }.size
        return Change(old.system != next.system, old.tools != next.tools, unchanged, old.messages.size, next.messages.size)
    }

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
