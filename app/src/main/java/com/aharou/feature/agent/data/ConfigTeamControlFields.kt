package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.subagent.AgentTeamStageForm
import com.aharou.feature.agent.domain.subagent.TeamCommunication
import com.aharou.feature.agent.domain.subagent.toForm
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigTeamControlFields @Inject constructor(private val teams: AgentTeamRepository) {
    fun registerInto(registry: ConfigRegistry) { registry.register(Stages()) }

    private fun entries(): Map<String, Pair<AgentTeamEntry, Int>> = buildMap {
        teams.listAll().forEach { entry -> entry.team.stages.indices.forEach { index ->
            val nameId = entry.team.name.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            put("${entry.scope.name.lowercase()}:$nameId:${index + 1}", entry to index)
        } }
    }

    private fun stage(id: String): AgentTeamStageForm {
        val (entry, index) = entries()[id] ?: throw ConfigError.InvalidValue("Unknown team stage")
        return entry.team.toForm(entry.scope).stages[index]
    }

    private fun update(id: String, change: (AgentTeamStageForm) -> AgentTeamStageForm) {
        val (entry, index) = entries()[id] ?: throw ConfigError.InvalidValue("Unknown team stage")
        val form = entry.team.toForm(entry.scope)
        // Retain per-task prompts and write leases instead of expanding the shared prompt again.
        val stages = form.stages.mapIndexed { i, item -> (if (i == index) change(item) else item).copy(prompt = "") }
        teams.save(form.copy(stages = stages), entry.team.name)?.let {
            throw ConfigError.InvalidValue("Failed to save team: $it")
        }
    }

    private inner class Stages : ConfigCollection {
        override val basePath = "team.stages"
        override val displayName = "Team communication"
        override val description = "Stage ids are scope:UTF8-hex-team-name:position. Changes apply to the next run."
        override val addable = false
        override val removable = false
        override fun childIds(): List<String> = entries().keys.toList()
        override fun fields(forId: String): List<ConfigField> {
            if (forId !in entries()) return emptyList()
            return listOf(
                ClosureField(path = "$basePath.$forId.communication", displayName = "Communication scope",
                    description = "same_stage, parent_only or run; enforced during message routing.", valueSchema = ConfigSchema.Str(),
                    reader = { ConfigValue.Str(stage(forId).communication.token) }, writer = { value ->
                        val policy = TeamCommunication.fromToken((value as? ConfigValue.Str)?.value)
                            ?: throw ConfigError.InvalidValue("Expected same_stage, parent_only or run")
                        update(forId) { it.copy(communication = policy) }
                    }),
                ClosureField(path = "$basePath.$forId.message_limit", displayName = "Message budget",
                    description = "Per member per run, shared across rework rounds.", valueSchema = ConfigSchema.Int(1, 100),
                    reader = { ConfigValue.Int(stage(forId).messageLimit) }, writer = { value ->
                        val limit = (value as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("integer")
                        if (limit !in 1..100) throw ConfigError.InvalidValue("Expected 1..100")
                        update(forId) { it.copy(messageLimit = limit) }
                    }),
                ClosureField(path = "$basePath.$forId.communication_guide", displayName = "Communication guide",
                    description = "Stage-specific collaboration instructions, without extending task or write authority.", valueSchema = ConfigSchema.Str(),
                    reader = { ConfigValue.Str(stage(forId).communicationGuide) }, writer = { value ->
                        val guide = (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                        update(forId) { it.copy(communicationGuide = guide) }
                    })
            )
        }
        override fun add(payload: ConfigValue): String = throw ConfigError.PermissionDenied("Use the team editor")
        override fun remove(id: String) { throw ConfigError.PermissionDenied("Use the team editor") }
    }
}
