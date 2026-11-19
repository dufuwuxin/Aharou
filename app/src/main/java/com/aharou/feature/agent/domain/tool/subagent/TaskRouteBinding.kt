package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.model.RouteGroupAllocation
import com.aharou.feature.agent.domain.model.RouteModelBinding
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.ModelRouteGroupRepository
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRouteBinding @Inject constructor(
    private val groups: ModelRouteGroupRepository,
    private val defaults: DefaultModelSettingsRepository,
    private val providers: AIProviderRepository,
    private val allocation: RouteGroupAllocation,
) {
    data class Selection(val binding: RouteModelBinding, val parentId: String, val group: ModelRouteGroup?, val ordered: Boolean)

    suspend fun prepare(parent: ChatSessionEntity, provider: String?, model: String?): Selection {
        val selection = parent.providerId?.takeIf { it.isNotBlank() } ?: defaults.getDefaultProviderId()
        val id = ModelRouteGroup.idFromSelection(selection)
        if (id == null) return Selection(RouteModelBinding(provider, model), parent.id, null, false)
        val group = groups.get(id) ?: error("MODEL_ROUTE_GROUP_REMOVED")
        val ordered = provider == null && model == null
        val binding = if (ordered) allocation.peek(parent.id, group) else {
            val all = providers.getAllProviders().first()
            val chosenProvider = provider ?: group.models.firstOrNull { it.model == model }?.providerId
                ?: all.firstOrNull { model in it.models && it.isEnabled && it.hasUsableApiKey }?.id
                ?: error("MODEL_ROUTE_FIXED_MODEL_UNAVAILABLE")
            val config = all.firstOrNull { it.id == chosenProvider } ?: error("MODEL_ROUTE_PROVIDER_REMOVED")
            RouteModelBinding(chosenProvider, model ?: config.effectiveModel)
        }
        val config = providers.getProviderById(requireNotNull(binding.providerId)) ?: error("MODEL_ROUTE_PROVIDER_REMOVED")
        check(config.isEnabled && config.hasUsableApiKey && binding.model in config.models) { "MODEL_ROUTE_MODEL_UNAVAILABLE" }
        return Selection(binding, parent.id, group, ordered)
    }

    suspend fun saveMember(session: ChatSessionEntity, selection: Selection, save: suspend () -> Unit) {
        if (selection.group == null) { save(); return }
        groups.markMember(session.id, true)
        try { save() }
        catch (failure: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { groups.markMember(session.id, false) }
            throw failure
        }
    }

    fun committed(selection: Selection) {
        if (selection.ordered) allocation.advance(selection.parentId, requireNotNull(selection.group))
    }
}
