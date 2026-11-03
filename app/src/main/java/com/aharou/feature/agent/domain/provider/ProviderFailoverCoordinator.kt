package com.aharou.feature.agent.domain.provider

import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.FallbackTarget
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨供应商 / 跨模型的「备用模型链」会话级状态。
 *
 * 选路规则（与设置里那条备用链一一对应）：
 *   1. 主供应商失败（额度耗尽 / 鉴权 / 限流）→ 切到链上第 1 条重发本轮；
 *   2. 第 1 条也失败 → 切第 2 条；依此往下，直到链走完；
 *   3. 链走完仍失败 → 撤销覆盖、上报原始错误，下一轮回到主供应商重新开始。
 *
 * 之所以按会话粘住而不是「失败就切一次」：主供应商额度耗尽后，后续每一轮都会先撞一次失败
 * （白等一次首字超时），粘住之后直接走备用，直到备用自己也不可用或用户在设置里改动。
 *
 * 抽成独立类还有个现实原因：[com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow]
 * 有单文件行数棘轮，逻辑放外面才不会让门禁失败。
 */
@Singleton
class ProviderFailoverCoordinator @Inject constructor(
    private val providerRepository: AIProviderRepository
) {

    /**
     * 一次降级计划：整条备用链 + 当前已经切到的下标（-1 表示还没切过）。
     *
     * 链在第一次切换时就固化下来，避免用户中途改设置让「往下走」跳到别的链上。
     */
    private class Plan(val chain: List<FallbackTarget>, val cursor: Int)

    private val plans = ConcurrentHashMap<String, Plan>()

    private fun sessionKey(sessionId: String?): String =
        sessionId?.takeIf { it.isNotBlank() } ?: NO_SESSION

    /** 该会话当前是否已切在备用模型上。 */
    fun isActive(sessionId: String?): Boolean =
        (plans[sessionKey(sessionId)]?.cursor ?: -1) >= 0

    /**
     * 已激活时返回当前这一跳的配置；这一跳被删 / 禁用 / 丢 Key 就撤销覆盖返回 null，
     * 由调用方按「主供应商」继续，而不是整轮卡在坏目标上。
     */
    suspend fun overrideConfig(sessionId: String?): AIProviderConfig? {
        val key = sessionKey(sessionId)
        val plan = plans[key] ?: return null
        val config = plan.chain.getOrNull(plan.cursor)?.let { resolve(it) }
        if (config == null) {
            plans.remove(key)
            FileLogger.w(TAG, "备用模型已不可用（被删 / 禁用 / 无 Key），撤销回退")
        }
        return config
    }

    /**
     * 沿备用链往下走一跳，返回切到的目标配置。
     *
     * [origin] 只在本会话还没有降级计划时用到：它的 [AIProviderConfig.effectiveFallbackChain]
     * 就是这次要用整条链。整条链都不可用（被删 / 禁用 / 无 Key / 无模型）时返回 null，
     * 调用方按原错误处理，不要在这里抛。
     */
    suspend fun next(sessionId: String?, origin: AIProviderConfig): AIProviderConfig? {
        val key = sessionKey(sessionId)
        val existing = plans[key]
        val chain = existing?.chain ?: chainOf(origin)
        var index = (existing?.cursor ?: -1) + 1
        while (index < chain.size) {
            val config = resolve(chain[index])
            if (config != null) {
                plans[key] = Plan(chain, index)
                FileLogger.w(
                    TAG,
                    "备用模型切换：${origin.name} -> ${config.name}(${config.effectiveModel})，第 ${index + 1}/${chain.size} 跳"
                )
                return config
            }
            index++
        }
        // 链走到底：撤销覆盖，下一轮回到主供应商，不再反复弹同一条错误
        plans.remove(key)
        return null
    }

    /** 主动撤销覆盖（用户改设置 / 会话结束）。 */
    fun deactivate(sessionId: String?) {
        plans.remove(sessionKey(sessionId))
    }

    /** 链上的一条能真正发请求吗：供应商在、启用、有 Key、模型非空。 */
    private suspend fun resolve(target: FallbackTarget): AIProviderConfig? {
        val provider = providerRepository.getProviderById(target.providerId) ?: return null
        if (!provider.isEnabled || !provider.hasUsableApiKey) return null
        // 备用模型可以不在目标的模型列表里：用户可能手填了新模型，交给请求本身报错，不在这里拦
        val model = target.model.trim().ifBlank { provider.effectiveModel }
        if (model.isBlank()) return null
        return provider.copy(selectedModel = model)
    }

    /** 链本身：去掉空条与指向自己的自环（自环会让「往下切」原地打转）。 */
    private fun chainOf(origin: AIProviderConfig): List<FallbackTarget> =
        origin.effectiveFallbackChain
            .filter { it.providerId.isNotBlank() && it.providerId != origin.id }
            .distinct()

    private companion object {
        const val TAG = "ProviderFailover"
        const val NO_SESSION = "__no_session__"
    }
}
