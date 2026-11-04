package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigRegistry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigAgentControlFields @Inject constructor(
    private val skills: ConfigSkillFields,
    private val teams: ConfigTeamControlFields,
    private val tools: ConfigToolControlFields
) {
    fun registerInto(registry: ConfigRegistry) {
        skills.registerInto(registry)
        teams.registerInto(registry)
        tools.registerInto(registry)
    }
}
