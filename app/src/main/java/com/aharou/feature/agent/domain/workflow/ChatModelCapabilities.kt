package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AnthropicAdapter
import com.aharou.feature.agent.domain.provider.GeminiAdapter
import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.domain.model.ModelContextPolicy
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.ProviderType

internal suspend fun ModelMetadataService.configureChatModel(provider: AIProvider): ModelMetadata {
    val type = when (provider) {
        is AnthropicAdapter -> ProviderType.ANTHROPIC
        is GeminiAdapter -> ProviderType.GEMINI
        else -> ProviderType.OPENAI
    }
    val metadata = resolve(provider.providerId, type, provider.model)
    if (provider is AnthropicAdapter) provider.maxOutputTokens = ModelContextPolicy.outputReserveTokens(metadata)
    return metadata
}
