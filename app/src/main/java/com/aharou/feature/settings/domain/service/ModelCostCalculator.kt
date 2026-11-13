package com.aharou.feature.settings.domain.service

import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM 调用费用估算。单价经 [ModelMetadataService] 按「渠道自定义元数据 → models.dev → 内置」三级回退解析，
 * 所以必须带上渠道（providerId + type），只按模型算会取不到渠道自定义单价。
 */
@Singleton
class ModelCostCalculator @Inject constructor(
    private val modelMetadataService: ModelMetadataService
) {
    /**
     * 一组 token 用量的预估费用（USD）；模型无单价返回 null。
     * 已使用的计费项缺少单价时返回 null；显式零价与未知单价不同。
     */
    suspend fun costUsd(
        providerId: String,
        providerType: ProviderType,
        model: String,
        inputTokens: Long,
        cachedInputTokens: Long,
        outputTokens: Long,
        cacheCreationTokens: Long = 0
    ): Double? {
        val meta = modelMetadataService.resolve(providerId, providerType, model)
        return pricedTokenCost(inputTokens, cachedInputTokens, outputTokens, cacheCreationTokens,
            meta.inputCostUsdPerM, meta.outputCostUsdPerM, meta.cacheReadCostUsdPerM, meta.cacheWriteCostUsdPerM)
    }

}

internal fun pricedTokenCost(
    input: Long, cacheRead: Long, output: Long, cacheWrite: Long,
    inputPrice: Double?, outputPrice: Double?, readPrice: Double?, writePrice: Double?,
): Double? {
    if (input < 0 || output < 0 || cacheRead < 0 || cacheWrite < 0 || cacheRead > input || cacheWrite > input - cacheRead) return null
    val items = listOf(input - cacheRead - cacheWrite to inputPrice, output to outputPrice,
        cacheRead to readPrice, cacheWrite to writePrice)
    var cost = 0.0
    for ((tokens, rate) in items) {
        if (tokens == 0L) continue
        if (rate == null || !rate.isFinite() || rate < 0) return null
        cost += tokens * rate / 1_000_000.0
    }
    return cost.takeIf { it.isFinite() }
}
