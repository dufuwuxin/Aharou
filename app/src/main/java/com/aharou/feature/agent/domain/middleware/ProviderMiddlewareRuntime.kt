package com.aharou.feature.agent.domain.middleware

import com.aharou.core.util.FileLogger

/**
 * 声明式中间件在「不碰请求体」这一层能落地的两部分：模型映射。
 *
 * 参数覆盖（[MiddlewareConfig.paramOverrides]）作用在请求 JSON 上，需要按三种协议各自的组装点
 * 插入，暂不在这一层处理——配置里有它也不会被静默忽略：调用方可以拿 [MiddlewareConfig.paramOverrides]
 * 自行判断是否要提示用户。
 */
data class MiddlewareApplication(
    /** 真正发给服务端的模型名（未配置映射时与请求名相同）。 */
    val actualModel: String,
    /** 原生记忆库生成的系统提示，不从中间件追加正文。 */
    val systemPrompt: String,
)

/**
 * 计算一次请求的中间件效果。
 *
 * @param rawConfig 供应商上的 JSON 配置原文；空/非法 JSON 按「未配置」处理，不抛异常。
 * @param requestedModel 用户在 UI 上选的模型名，用于查找 [MiddlewareConfig.modelMap]。
 */
fun applyProviderMiddleware(
    rawConfig: String?,
    providerId: String,
    providerType: String,
    requestedModel: String,
    systemPrompt: String,
): MiddlewareApplication {
    val parsed = RequestMiddleware.parseConfig(rawConfig)
    if (parsed == null && !rawConfig.isNullOrBlank()) {
        FileLogger.w("ProviderMiddleware", "中间件配置解析失败（已忽略）：${rawConfig.take(120)}")
    }
    val config = parsed?.takeIf { it.enabled } ?: return MiddlewareApplication(requestedModel, systemPrompt)

    val mapped = config.modelMap[requestedModel]?.takeIf { it.isNotBlank() } ?: requestedModel
    return MiddlewareApplication(mapped, systemPrompt)
}
