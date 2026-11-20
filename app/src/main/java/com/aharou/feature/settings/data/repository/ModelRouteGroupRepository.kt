package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteGroupCodec
import com.aharou.feature.settings.domain.model.ModelRouteGroupForm
import com.aharou.feature.settings.domain.model.ModelRouteMember
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.modelRouteGroupDataStore by preferencesDataStore(
    name = "model_route_groups_prefs",
    corruptionHandler = preferencesCorruptionHandler,
)

@Singleton
class ModelRouteGroupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private companion object {
        val GROUPS_KEY = stringPreferencesKey("groups_json")
        val MEMBERS_KEY = androidx.datastore.preferences.core.stringSetPreferencesKey("allocated_member_sessions")
    }
    private val writeMutex = Mutex()

    val groups: Flow<List<ModelRouteGroup>> = context.modelRouteGroupDataStore.data.map {
        runCatching { ModelRouteGroupCodec.decode(it[GROUPS_KEY].orEmpty()) }.getOrDefault(emptyList())
    }

    suspend fun isAllocatedMember(session: String): Boolean = session in context.modelRouteGroupDataStore.data.first()[MEMBERS_KEY].orEmpty()

    suspend fun markMember(session: String, allocated: Boolean) {
        context.modelRouteGroupDataStore.edit {
            val current = it[MEMBERS_KEY].orEmpty()
            it[MEMBERS_KEY] = if (allocated) current + session else current - session
        }
    }

    suspend fun snapshot(): List<ModelRouteGroup> = groups.first()

    suspend fun get(id: String): ModelRouteGroup? = snapshot().firstOrNull { it.id == id }

    suspend fun create(name: String, models: List<ModelRouteMember>): ModelRouteGroup = writeMutex.withLock {
        val group = ModelRouteGroup(UUID.randomUUID().toString(), name.trim(), models).let(ModelRouteGroupCodec::validate)
        val next = snapshot() + group
        persist(next)
        group
    }

    suspend fun update(id: String, name: String, models: List<ModelRouteMember>): ModelRouteGroup = writeMutex.withLock {
        val nextGroup = ModelRouteGroup(id, name.trim(), models).let(ModelRouteGroupCodec::validate)
        val current = snapshot()
        require(current.any { it.id == id }) { "model route group not found: $id" }
        persist(current.map { if (it.id == id) nextGroup else it })
        nextGroup
    }

    suspend fun delete(id: String): Boolean = writeMutex.withLock {
        val current = snapshot()
        if (current.none { it.id == id }) return@withLock false
        persist(current.filterNot { it.id == id })
        true
    }

    private suspend fun persist(groups: List<ModelRouteGroup>) {
        context.modelRouteGroupDataStore.edit { it[GROUPS_KEY] = ModelRouteGroupCodec.encode(groups) }
    }
}
