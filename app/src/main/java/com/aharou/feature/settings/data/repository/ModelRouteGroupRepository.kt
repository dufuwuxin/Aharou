package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteGroupCodec
import com.aharou.feature.settings.domain.model.ModelRouteMember
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.modelRouteGroupDataStore by preferencesDataStore(
    name = "model_route_groups_prefs",
    corruptionHandler = preferencesCorruptionHandler,
)

class ModelRouteGroupDataException(cause: Exception) : IllegalStateException("MODEL_ROUTE_GROUP_DATA_INVALID", cause)

@Singleton
class ModelRouteGroupRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.modelRouteGroupDataStore)

    private companion object {
        val GROUPS_KEY = stringPreferencesKey("groups_json")
        val MEMBERS_KEY = androidx.datastore.preferences.core.stringSetPreferencesKey("allocated_member_sessions")
    }
    val readState: Flow<Result<List<ModelRouteGroup>>> = dataStore.data.map { preferences ->
        try {
            Result.success(preferences.readGroups())
        } catch (failure: ModelRouteGroupDataException) {
            Result.failure(failure)
        }
    }

    // Existing list subscribers keep the last valid value; corruption is exposed through readState.
    val groups: Flow<List<ModelRouteGroup>> = readState.mapNotNull { it.getOrNull() }

    suspend fun isAllocatedMember(session: String): Boolean = session in dataStore.data.first()[MEMBERS_KEY].orEmpty()

    suspend fun markMember(session: String, allocated: Boolean) {
        dataStore.edit {
            val current = it[MEMBERS_KEY].orEmpty()
            it[MEMBERS_KEY] = if (allocated) current + session else current - session
        }
    }

    suspend fun snapshot(): List<ModelRouteGroup> = readState.first().getOrThrow()

    suspend fun get(id: String): ModelRouteGroup? = snapshot().firstOrNull { it.id == id }

    suspend fun create(name: String, models: List<ModelRouteMember>): ModelRouteGroup {
        val group = ModelRouteGroup(UUID.randomUUID().toString(), name.trim(), models).let(ModelRouteGroupCodec::validate)
        dataStore.edit { preferences ->
            val current = preferences.readGroups()
            preferences[GROUPS_KEY] = ModelRouteGroupCodec.encode(current + group)
        }
        return group
    }

    suspend fun update(id: String, name: String, models: List<ModelRouteMember>): ModelRouteGroup {
        val nextGroup = ModelRouteGroup(id, name.trim(), models).let(ModelRouteGroupCodec::validate)
        dataStore.edit { preferences ->
            val current = preferences.readGroups()
            require(current.any { it.id == id }) { "model route group not found: $id" }
            preferences[GROUPS_KEY] = ModelRouteGroupCodec.encode(current.map { if (it.id == id) nextGroup else it })
        }
        return nextGroup
    }

    suspend fun delete(id: String): Boolean {
        var removed = false
        dataStore.edit { preferences ->
            val current = preferences.readGroups()
            if (current.any { it.id == id }) {
                preferences[GROUPS_KEY] = ModelRouteGroupCodec.encode(current.filterNot { it.id == id })
                removed = true
            }
        }
        return removed
    }

    private fun Preferences.readGroups(): List<ModelRouteGroup> {
        val raw = this[GROUPS_KEY] ?: return emptyList()
        return try {
            require(raw.isNotBlank()) { "model route groups JSON must not be blank" }
            ModelRouteGroupCodec.decode(raw)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            throw ModelRouteGroupDataException(failure)
        }
    }
}
