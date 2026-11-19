package com.aharou.feature.agent.domain.model

import com.aharou.feature.settings.domain.model.ModelRouteGroup
import javax.inject.Inject
import javax.inject.Singleton

data class RouteModelBinding(val providerId: String?, val model: String?)

@Singleton
class RouteGroupAllocation @Inject constructor() {
    private val nextSlot = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Long>()

    @Synchronized
    fun peek(parent: String, group: ModelRouteGroup): RouteModelBinding {
        require(group.models.isNotEmpty())
        val slot = ((nextSlot[parent to group.id] ?: 1L) % group.models.size).toInt()
        return group.models[slot].let { RouteModelBinding(it.providerId, it.model) }
    }

    @Synchronized
    fun advance(parent: String, group: ModelRouteGroup) {
        nextSlot[parent to group.id] = (nextSlot[parent to group.id] ?: 1L) + 1L
    }

    @Synchronized
    fun assign(parentSessionId: String, group: ModelRouteGroup?, providerId: String? = null, model: String? = null): RouteModelBinding? {
        if (providerId != null || model != null) {
            val matched = group?.models?.firstOrNull { (providerId == null || it.providerId == providerId) && (model == null || it.model == model) }
            return RouteModelBinding(providerId ?: matched?.providerId, model ?: matched?.model)
        }
        if (group == null || group.models.isEmpty()) return null
        return peek(parentSessionId, group).also { advance(parentSessionId, group) }
    }
}
