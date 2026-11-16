package com.aharou.feature.settings.data.account

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AIStreamChunk
import com.aharou.feature.agent.domain.tool.AgentTool
import kotlinx.coroutines.flow.Flow
import java.net.URI

/** Website text mode is explicitly non-agentic; unsupported history is still rejected by its gateway. */
internal class WebsiteTextProvider(private val delegate: AIProvider) : AIProvider by delegate {
    override var temperature: Float?
        get() = null
        set(value) { delegate.temperature = null }
    override var useResponseApi: Boolean
        get() = false
        set(value) { delegate.useResponseApi = false }

    override var customHeaders: Map<String, String>
        get() = delegate.customHeaders
        set(value) { delegate.customHeaders = value.filterKeys { !it.equals(com.aharou.feature.agent.domain.provider.OpenAIServiceTier.HEADER, true) } }

    init {
        delegate.temperature = null; delegate.useResponseApi = false
        customHeaders = delegate.customHeaders
        (delegate as? com.aharou.feature.agent.domain.provider.OpenAIAdapter)?.chatCacheKeyEnabled = false
    }

    override suspend fun complete(systemPrompt: String, messages: List<AgentMessage>, tools: List<AgentTool>, reasoningEffort: String?): AIResponse =
        delegate.complete(systemPrompt, messages, emptyList(), null)

    override fun completeStream(systemPrompt: String, messages: List<AgentMessage>, tools: List<AgentTool>, reasoningEffort: String?): Flow<AIStreamChunk> =
        delegate.completeStream(systemPrompt, messages, emptyList(), null)

    companion object {
        fun wrap(provider: AIProvider): AIProvider {
            val url = runCatching { URI(provider.baseUrl) }.getOrNull()
            return if (url?.scheme == "https" && url.host == AccountProviderManager.SENTINEL &&
                url.rawUserInfo == null && url.port in setOf(-1, 443) && url.rawQuery == null && url.rawFragment == null &&
                url.rawPath.split('/').let { it.size == 4 && it[1] in setOf(AccountProviderKind.DOUBAO.wireId, AccountProviderKind.DEEPSEEK.wireId, AccountProviderKind.CODEARTS.wireId) && it[2].isNotBlank() && it[3] == "v1" }) WebsiteTextProvider(provider) else provider
        }
    }
}
