package com.aharou.feature.settings.domain.model

internal inline fun resolveModelRoute(
    group: ModelRouteGroup?,
    findProvider: (String) -> AIProviderConfig?,
): AIProviderConfig {
    val route = group ?: error("MODEL_ROUTE_GROUP_REMOVED")
    for (member in route.models) {
        val provider = findProvider(member.providerId) ?: continue
        if (provider.isEnabled && provider.hasUsableApiKey && member.model in provider.models) {
            return provider.copy(selectedModel = member.model)
        }
    }
    error("MODEL_ROUTE_MODEL_UNAVAILABLE")
}
