package com.aharou.feature.agent.domain.middleware

import com.aharou.core.privacy.SystemPromptSanitizer
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class MiddlewareApplication(val actualModel: String, val systemPrompt: String)

fun applyProviderMiddleware(
    rawConfig: String?,
    providerId: String,
    providerType: String,
    requestedModel: String,
    systemPrompt: String,
): MiddlewareApplication {
    val parsed = RequestMiddleware.parseConfig(rawConfig)
    if (parsed == null && !rawConfig.isNullOrBlank()) {
        FileLogger.w("ProviderMiddleware", "中间件配置解析失败（已忽略）")
    }
    val config = parsed?.takeIf { it.enabled } ?: return MiddlewareApplication(requestedModel, systemPrompt)
    val mapped = config.modelMap[requestedModel]?.takeIf { it.isNotBlank() } ?: requestedModel
    return MiddlewareApplication(mapped, projectMiddlewareSystemPrompt(config, systemPrompt))
}

fun projectMiddlewareSystemPrompt(config: MiddlewareConfig?, systemPrompt: String): String =
    if (config?.enabled == true && config.sanitizeSystemPrompt) SystemPromptSanitizer.sanitize(systemPrompt) else systemPrompt

/** Only projects assistant body text. User/tool objects and native signed reasoning remain intact. */
fun projectMiddlewareHistory(config: MiddlewareConfig?, messages: List<AgentMessage>): List<AgentMessage> {
    if (config?.enabled != true || !config.stripAssistantThink) return messages
    return messages.map { message ->
        if (message is AgentMessage.AssistantMessage) {
            val content = SystemPromptSanitizer.stripAssistantThink(message.content)
            if (content == message.content) message else message.copy(content = content)
        } else message
    }
}

internal fun transformMiddlewareText(config: MiddlewareConfig, body: JsonObject): JsonObject {
    if (!config.sanitizeSystemPrompt && !config.stripAssistantThink) return body
    fun JsonObject.field(key: String, value: JsonElement) = JsonObject(toMutableMap().apply { put(key, value) })
    fun text(value: JsonElement, transform: (String) -> String): JsonElement = when (value) {
        is JsonPrimitive -> if (value.isString) JsonPrimitive(transform(value.content)) else value
        is JsonArray -> JsonArray(value.map { block ->
            if (block is JsonObject && (block["type"] as? JsonPrimitive)?.content in setOf("text", "input_text", "output_text")) {
                val original = block["text"] as? JsonPrimitive
                if (original?.isString == true) block.field("text", JsonPrimitive(transform(original.content))) else block
            } else block
        })
        else -> value
    }
    var result = body
    if (config.sanitizeSystemPrompt) {
        for (key in listOf("system", "instructions")) {
            result[key]?.let { result = result.field(key, text(it, SystemPromptSanitizer::sanitize)) }
        }
    }
    for (key in listOf("messages", "input")) {
        val messages = result[key] as? JsonArray ?: continue
        result = result.field(key, JsonArray(messages.map { item ->
            val message = item as? JsonObject ?: return@map item
            val role = (message["role"] as? JsonPrimitive)?.content
            val transform: ((String) -> String)? = when {
                config.sanitizeSystemPrompt && role in setOf("system", "developer") -> SystemPromptSanitizer::sanitize
                config.stripAssistantThink && role == "assistant" -> SystemPromptSanitizer::stripAssistantThink
                else -> null
            }
            val content = message["content"]
            if (transform == null || content == null) message else message.field("content", text(content, transform))
        }))
    }
    return result
}

/** Unknown/missing usage is deliberately not represented as a measured zero-percent hit rate. */
fun measuredCacheHitRatio(inputTokens: Int, cachedInputTokens: Int): Double? =
    if (inputTokens > 0 && cachedInputTokens in 1..inputTokens) cachedInputTokens.toDouble() / inputTokens else null
