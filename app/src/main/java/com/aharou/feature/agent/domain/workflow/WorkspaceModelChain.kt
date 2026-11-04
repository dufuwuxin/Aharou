package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.settings.data.repository.WorkspaceModelChainRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.FallbackTarget
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceModelChain @Inject constructor(
    private val settings: WorkspaceModelChainRepository,
    private val providers: AIProviderRepository
) {
    private data class Plan(val workspace: String, val chain: List<FallbackTarget>, val cursor: Int)
    private val plans = ConcurrentHashMap<String, Plan>()

    suspend fun begin(workspace: String, session: String?, mainChat: Boolean): AIProviderConfig? {
        if (session == null) return null
        if (!mainChat) { plans.remove(session); return null }
        val chain = settings.get(workspace)
        if (chain.isEmpty()) { plans.remove(session); return null }
        val previous = plans[session]
        val cursor = previous?.takeIf { it.workspace == workspace && it.chain == chain && it.cursor < chain.size }?.cursor ?: 0
        val plan = Plan(workspace, chain, cursor)
        plans[session] = plan
        return resolveFrom(session, plan) ?: error("工作区模型列表全部不可用，请检查供应商、模型与凭据")
    }

    fun configured(session: String?): Boolean = session != null && plans.containsKey(session)

    suspend fun current(session: String?): AIProviderConfig? {
        val plan = session?.let(plans::get) ?: return null
        return resolveFrom(session, plan) ?: error("工作区模型列表已耗尽")
    }

    suspend fun next(session: String?): AIProviderConfig? {
        val plan = session?.let(plans::get) ?: return null
        return resolveFrom(session, plan.copy(cursor = plan.cursor + 1))
    }

    // 每轮冻结候选列表；只前进不绕回，同供应商的不同模型也是不同候选。
    private suspend fun resolveFrom(session: String, plan: Plan): AIProviderConfig? {
        for (index in plan.cursor until plan.chain.size) {
            val target = plan.chain[index]
            val config = providers.getProviderById(target.providerId) ?: continue
            if (!config.isEnabled || !config.hasUsableApiKey || target.model.isBlank()) continue
            plans[session] = plan.copy(cursor = index)
            return config.copy(selectedModel = target.model)
        }
        plans[session] = plan.copy(cursor = plan.chain.size)
        return null
    }
}
