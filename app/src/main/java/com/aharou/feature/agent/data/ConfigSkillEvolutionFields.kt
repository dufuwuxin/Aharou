package com.aharou.feature.agent.data

import android.content.Context
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** User opt-in for generating an editable skill draft after a successful tool-using turn. */
@Singleton
class ConfigSkillEvolutionFields @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun registerInto(registry: ConfigRegistry) {
        registry.register(ClosureField(
            path = PATH,
            displayName = "Skill evolution suggestions",
            description = "When enabled, a successful turn that used tools may produce a reviewable skill draft. Drafts are never saved automatically.",
            valueSchema = ConfigSchema.Bool,
            reader = { ConfigValue.Bool(enabled()) },
            writer = { value ->
                val enabled = (value as? ConfigValue.Bool)?.value
                    ?: throw com.aharou.core.config.ConfigError.TypeMismatch("boolean")
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, enabled).apply()
            }
        ))
    }

    fun enabled(): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, false)

    companion object {
        const val PATH = "agent.skill_evolution_enabled"
        private const val PREFERENCES = "agent_skill_evolution"
        private const val KEY_ENABLED = "enabled"
    }
}
