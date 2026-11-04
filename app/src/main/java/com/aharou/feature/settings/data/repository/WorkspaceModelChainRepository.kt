package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.feature.settings.domain.model.FallbackTarget
import com.aharou.feature.settings.domain.model.encodeFallbackChain
import com.aharou.feature.settings.domain.model.parseFallbackChain
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.workspaceModelChains by preferencesDataStore(
    name = "workspace_model_chains", corruptionHandler = preferencesCorruptionHandler
)

@Singleton
class WorkspaceModelChainRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun get(workspace: String): List<FallbackTarget> {
        if (workspace.isBlank()) return emptyList()
        return parseFallbackChain(context.workspaceModelChains.data.first()[stringPreferencesKey(workspace)].orEmpty())
    }

    suspend fun set(workspace: String, chain: List<FallbackTarget>) {
        require(workspace.isNotBlank())
        val normalized = chain.map { FallbackTarget(it.providerId.trim(), it.model.trim()) }.distinct()
        require(normalized.all { it.providerId.isNotBlank() && it.model.isNotBlank() &&
            !it.providerId.contains('|') && !it.model.contains('|') &&
            !it.providerId.contains('\n') && !it.model.contains('\n') })
        context.workspaceModelChains.edit {
            val key = stringPreferencesKey(workspace)
            if (normalized.isEmpty()) it.remove(key) else it[key] = encodeFallbackChain(normalized)
        }
    }
}
