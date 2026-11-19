package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.defaultModelDataStore by preferencesDataStore(
    name = "default_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「新会话默认模型」（providerId + model 两字符串）。
 *
 * 用户在空会话中切换模型时，除绑定当前会话外还会写入此处；之后新建会话
 * 默认绑定该模型（见 AIAgentViewModel.createSession）。未设置时新建会话不绑定，
 * 回退全局 active provider。DataStore 用法与 VisionModelSettingsRepository 一致。
 */
@Singleton
class DefaultModelSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val routeGroupRepository: ModelRouteGroupRepository,
) {
    private companion object {
        val PROVIDER_ID_KEY = stringPreferencesKey("default_provider_id")
        val MODEL_KEY = stringPreferencesKey("default_model")
    }

    /** 当前持久化的新会话默认 providerId 流；未设置时为空字符串。 */
    val providerIdFlow: Flow<String> = context.defaultModelDataStore.data.map { it[PROVIDER_ID_KEY] ?: "" }

    /** 当前持久化的新会话默认 model 流；未设置时为空字符串。 */
    val modelFlow: Flow<String> = context.defaultModelDataStore.data.map { it[MODEL_KEY] ?: "" }

    val routeGroupsFlow: Flow<List<ModelRouteGroup>> = routeGroupRepository.groups

    suspend fun getRouteGroup(id: String): ModelRouteGroup? = routeGroupRepository.get(id)

    suspend fun isRouteSelection(session: com.aharou.feature.agent.data.local.entity.ChatSessionEntity?): Boolean =
        (session?.providerId?.takeIf(String::isNotBlank) ?: getDefaultProviderId()).startsWith("route:")

    suspend fun resolveRouting(session: com.aharou.feature.agent.data.local.entity.ChatSessionEntity?, providers: com.aharou.feature.settings.domain.repository.AIProviderRepository): com.aharou.feature.settings.domain.model.AIProviderConfig? {
        val selection = session?.providerId?.takeIf(String::isNotBlank) ?: getDefaultProviderId()
        if (selection.startsWith("route:")) return resolveRoute(selection, providers)
        if (session?.parentId != null && routeGroupRepository.isAllocatedMember(session.id)) {
            val provider = session.providerId?.let { providers.getProviderById(it) } ?: error("MODEL_ROUTE_ASSIGNED_PROVIDER_REMOVED")
            check(provider.isEnabled && provider.hasUsableApiKey && session.model in provider.models) { "MODEL_ROUTE_ASSIGNED_MODEL_UNAVAILABLE" }
            return provider.copy(selectedModel = requireNotNull(session.model))
        }
        return null
    }

    suspend fun resolveRoute(selection: String, providers: com.aharou.feature.settings.domain.repository.AIProviderRepository): com.aharou.feature.settings.domain.model.AIProviderConfig {
        val id = requireNotNull(ModelRouteGroup.idFromSelection(selection))
        val member = routeGroupRepository.get(id)?.models?.firstOrNull() ?: error("MODEL_ROUTE_GROUP_REMOVED")
        val provider = providers.getProviderById(member.providerId) ?: error("MODEL_ROUTE_PROVIDER_REMOVED")
        check(provider.isEnabled && provider.hasUsableApiKey && member.model in provider.models) { "MODEL_ROUTE_MODEL_UNAVAILABLE" }
        return provider.copy(selectedModel = member.model)
    }

    /** 写入新会话默认模型（设空字符串即等同 [clear]）。 */
    suspend fun setDefaultModel(providerId: String, model: String) {
        context.defaultModelDataStore.edit {
            it[PROVIDER_ID_KEY] = providerId
            it[MODEL_KEY] = model
        }
    }

    suspend fun clearRoute(id: String) {
        context.defaultModelDataStore.edit {
            if (it[PROVIDER_ID_KEY] == "route:$id") { it.remove(PROVIDER_ID_KEY); it.remove(MODEL_KEY) }
        }
    }

    /** 清空配置——新建会话回退到全局 active provider。 */
    suspend fun clear() {
        context.defaultModelDataStore.edit {
            it.remove(PROVIDER_ID_KEY)
            it.remove(MODEL_KEY)
        }
    }

    /** 读取一次当前新会话默认 providerId（冷读用）。 */
    suspend fun getDefaultProviderId(): String = providerIdFlow.first()

    /** 读取一次当前新会话默认 model（冷读用）。 */
    suspend fun getDefaultModel(): String = modelFlow.first()
}
