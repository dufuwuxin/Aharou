package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.data.repository.DualModelSettingsRepository
import com.aharou.feature.agent.domain.provider.ProviderFailoverCoordinator
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.session.DualModelSettings
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DualModelRouter @Inject constructor(
    private val settings: DualModelSettingsRepository,
    private val sessions: SessionUseCase,
    private val providers: AIProviderRepository
) {
    private val failover = ProviderFailoverCoordinator(providers)
    private data class Selection(val key: String, val provider: AIProviderConfig)

    // Freeze choices, not mode: plan approval may change mode inside the same turn.
    suspend fun settingsForTurn(sessionId: String?): DualModelSettings {
        if (sessionId.isNullOrBlank()) return DualModelSettings()
        val session = sessions.getSessionById(sessionId) ?: return DualModelSettings()
        if (session.parentId != null) return DualModelSettings()
        return settings.get(sessionId)
    }

    suspend fun resolve(sessionId: String?, frozenSettings: DualModelSettings? = null): AIProviderConfig? {
        val selection = selection(sessionId, frozenSettings) ?: return null
        return failover.overrideConfig(selection.key) ?: selection.provider
    }

    suspend fun next(sessionId: String?, frozenSettings: DualModelSettings): AIProviderConfig? {
        val selection = selection(sessionId, frozenSettings) ?: return null
        return failover.next(selection.key, selection.provider)
    }

    private suspend fun selection(sessionId: String?, frozenSettings: DualModelSettings?): Selection? {
        if (sessionId.isNullOrBlank()) return null
        val session = sessions.getSessionById(sessionId) ?: return null
        if (session.parentId != null) return null
        val config = frozenSettings ?: settings.get(sessionId)
        if (!config.enabled) return null
        val mode = runCatching { AgentMode.valueOf(session.mode) }.getOrElse {
            error("Invalid session mode for dual model planning")
        }
        val target = checkNotNull(config.target(mode))
        val provider = providers.getProviderById(target.providerId)
            ?: error("Dual model provider is unavailable: ${target.providerId}")
        check(provider.isEnabled && provider.hasUsableApiKey) { "Dual model provider is disabled or unavailable: ${target.providerId}" }
        check(target.model in provider.models || target.model == provider.defaultModel || target.model == provider.selectedModel) {
            "Dual model selection is no longer available: ${target.model}"
        }
        val stage = if (mode == AgentMode.PLAN) "planning" else "execution"
        val key = "$sessionId:$stage:${target.providerId.length}:${target.providerId}:${target.model}"
        return Selection(key, provider.copy(selectedModel = target.model))
    }
}
