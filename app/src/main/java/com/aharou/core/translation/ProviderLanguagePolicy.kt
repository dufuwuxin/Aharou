package com.aharou.core.translation

import kotlinx.serialization.json.Json

/** Native is deliberately the default for existing providers and backups. */
enum class ProviderLanguagePolicy(val wireValue: String) {
    NATIVE("native"), CHINESE("zh"), ENGLISH("en");

    companion object {
        fun fromWire(value: String): ProviderLanguagePolicy = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported provider language")
    }
}

internal fun isStructuredTranslationPayload(text: String): Boolean {
    val value = text.trim()
    if (value.isEmpty()) return true
    if (value.first() !in "{[\"") return false
    return runCatching { Json.parseToJsonElement(value) }.isSuccess
}

fun interface ProviderTextTranslator {
    suspend fun translate(text: String, target: String): String
}
