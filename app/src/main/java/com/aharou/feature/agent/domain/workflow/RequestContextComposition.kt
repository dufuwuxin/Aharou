package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.modelToolResultText
import com.aharou.feature.settings.domain.model.ModelContextPolicy
import com.google.gson.Gson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The provider-neutral, folded and masked request; estimates are never provider billing. */
object RequestContextComposition {
    const val PREFIX = "aharou-request-composition:"
    private val gson = Gson()
    data class Part(val source: String, val characters: Int, val estimatedTokens: Int)
    data class Snapshot(val version: Long, val capturedAt: Long, val parts: List<Part>, val text: String) {
        val characters: Int get() = parts.sumOf { it.characters }
        val estimatedTokens: Int get() = parts.sumOf { it.estimatedTokens }
        fun metadata() = buildJsonObject {
            put("requestVersion", version)
            put("capturedAt", capturedAt)
            put("source", "final_outbound_folded_masked_request")
            put("tokenMethod", "local heuristic: text + tool definitions + framing + 4096/image; not provider billing")
            put("characters", characters)
            put("estimatedTokens", estimatedTokens)
            parts.forEach { put(it.source, it.estimatedTokens) }
        }
        fun displayText(): String = PREFIX + metadata() + "\n" + text
    }

    fun capture(version: Long, system: String, messages: List<AgentMessage>, tools: List<AgentTool>): Snapshot {
        val toolText = tools.joinToString("\n") { it.name + it.description + gson.toJson(it.toJsonSchema()) }
        val history = messages.joinToString("\n\n") { messageText(it) }
        val imageCount = messages.sumOf { images(it) }
        val parts = listOf(
            Part("system", codepoints(system), ModelContextPolicy.estimateTextTokens(system)),
            Part("tools", codepoints(toolText), tools.sumOf {
                ModelContextPolicy.estimateTextTokens(it.name + it.description + gson.toJson(it.toJsonSchema())) + 16
            }),
            Part("history", codepoints(history), messages.sumOf { ModelContextPolicy.estimateTextTokens(messageText(it)) + 12 }),
            Part("images", 0, imageCount * 4096)
        )
        // Image bytes stay out of the panel and tool response; count and MIME metadata suffice.
        val text = "## system\n$system\n\n## tools\n$toolText\n\n## history\n$history\n\n## images\n" +
            messages.flatMap { when (it) {
                is AgentMessage.UserMessage -> it.images
                is AgentMessage.AssistantMessage -> it.images
                is AgentMessage.ToolResultMessage -> it.images
            } }.joinToString("\n") { it.mimeType }
        return Snapshot(version, System.currentTimeMillis(), parts, text)
    }

    internal fun images(message: AgentMessage): Int = when (message) {
        is AgentMessage.UserMessage -> message.images.size
        is AgentMessage.AssistantMessage -> message.images.size
        is AgentMessage.ToolResultMessage -> message.images.size
    }
    internal fun messageText(message: AgentMessage): String = when (message) {
        is AgentMessage.UserMessage -> message.content
        is AgentMessage.AssistantMessage -> message.content + message.reasoning +
            message.toolCalls.joinToString { it.name + gson.toJson(it.arguments) } + message.signature + message.thinkingBlocksJson
        is AgentMessage.ToolResultMessage -> message.toolName +
            (message.modelResult ?: modelToolResultText(message.toolName, message.result) ?: message.result)
    }
    private fun codepoints(text: String) = text.codePointCount(0, text.length)
}
