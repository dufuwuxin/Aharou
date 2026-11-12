package com.aharou.feature.settings.presentation

import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentSaveError
import com.aharou.feature.agent.domain.subagent.InjectPart
import com.aharou.feature.agent.domain.subagent.KeywordInjection

data class SubAgentUiEntry(
    val name: String,
    val description: String,
    val scope: AgentDefinitionScope,
    val disabled: Boolean,
    val providerId: String?,
    val model: String?,
    val reasoningEffort: String?,
    val mode: AgentMode?,
    val allowedTools: List<String>,
    val disallowedTools: List<String>,
    val skills: List<String> = emptyList(),
    val inject: Set<InjectPart>,
    val keywordInjections: List<KeywordInjection> = emptyList(),
    val prompt: String,
    val filePath: String?,
    val tags: List<String> = emptyList()
)

data class SkillBindingSaveState(
    val skillName: String = "",
    val state: SubAgentSaveState = SubAgentSaveState.Idle
)

sealed interface SubAgentSaveState {
    data object Idle : SubAgentSaveState
    data object Saving : SubAgentSaveState
    data object Saved : SubAgentSaveState
    data class Failed(val error: AgentSaveError) : SubAgentSaveState
}
