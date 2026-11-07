package com.aharou.feature.agent.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.feature.agent.domain.session.DualModelSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// No corruption-to-default handler: a failed read must never overwrite existing choices.
private val Context.dualModelSettings by preferencesDataStore(name = "dual_model_sessions")

@Singleton
class DualModelSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun observe(sessionId: String): Flow<DualModelSettings> {
        require(sessionId.isNotBlank())
        return context.dualModelSettings.data.map { decode(it, sessionId) }
    }

    suspend fun get(sessionId: String): DualModelSettings = observe(sessionId).first()

    suspend fun update(sessionId: String, transform: (DualModelSettings) -> DualModelSettings) {
        require(sessionId.isNotBlank())
        context.dualModelSettings.edit { preferences ->
            val value = transform(decode(preferences, sessionId))
            require(!value.enabled || value.isConfigured) { "Configure both models before enabling dual model planning" }
            val planning = DualModelSettings.encodeTarget(value.planning)
            val execution = DualModelSettings.encodeTarget(value.execution)
            preferences[booleanPreferencesKey("$sessionId.enabled")] = value.enabled
            preferences[stringPreferencesKey("$sessionId.planning")] = planning
            preferences[stringPreferencesKey("$sessionId.execution")] = execution
        }
    }

    private fun decode(preferences: Preferences, sessionId: String): DualModelSettings = DualModelSettings(
        enabled = preferences[booleanPreferencesKey("$sessionId.enabled")] ?: false,
        planning = DualModelSettings.decodeTarget(preferences[stringPreferencesKey("$sessionId.planning")]),
        execution = DualModelSettings.decodeTarget(preferences[stringPreferencesKey("$sessionId.execution")])
    )
}
