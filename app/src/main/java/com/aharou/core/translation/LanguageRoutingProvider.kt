package com.aharou.core.translation

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AIStreamChunk
import com.aharou.feature.agent.domain.tool.AgentTool
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Only outbound prose is projected. Responses, reasoning, tool payloads and signatures stay raw. */
class LanguageRoutingProvider(
    private val delegate: AIProvider,
    private val policy: () -> ProviderLanguagePolicy,
    private val translator: ProviderTextTranslator,
) : AIProvider by delegate {
    private suspend fun prose(text: String, target: String): String =
        if (isStructuredTranslationPayload(text)) text else translator.translate(text, target)

    private suspend fun messages(source: List<AgentMessage>, target: String): List<AgentMessage> = source.map { message ->
        when (message) {
            is AgentMessage.UserMessage -> message.copy(content = prose(message.content, target))
            is AgentMessage.AssistantMessage -> message.copy(content = prose(message.content, target))
            // Tool results are transport envelopes, including JSON strings and textual command output.
            is AgentMessage.ToolResultMessage -> message
        }
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?,
    ): AIResponse {
        val selected = policy()
        if (selected == ProviderLanguagePolicy.NATIVE) return delegate.complete(systemPrompt, messages, tools, reasoningEffort)
        return delegate.complete(
            prose(systemPrompt, selected.wireValue),
            messages(messages, selected.wireValue),
            projectLanguageTools(tools, selected.wireValue, ::prose),
            reasoningEffort,
        )
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?,
    ): Flow<AIStreamChunk> = flow {
        val selected = policy()
        if (selected == ProviderLanguagePolicy.NATIVE) {
            emitAll(delegate.completeStream(systemPrompt, messages, tools, reasoningEffort))
        } else {
            emitAll(delegate.completeStream(
                prose(systemPrompt, selected.wireValue),
                messages(messages, selected.wireValue),
                projectLanguageTools(tools, selected.wireValue, ::prose),
                reasoningEffort,
            ))
        }
    }

    @Singleton
    class Factory @Inject constructor(
        private val settings: ProviderLanguageSettings,
        private val translation: OfflineTranslation,
    ) {
        fun wrap(provider: AIProvider): AIProvider = LanguageRoutingProvider(
            provider,
            { if (translation.enabled.value) settings.get(provider.providerId) else ProviderLanguagePolicy.NATIVE },
            ProviderTextTranslator { text, target -> translation.translateRequired(text, target) },
        )
    }
}
