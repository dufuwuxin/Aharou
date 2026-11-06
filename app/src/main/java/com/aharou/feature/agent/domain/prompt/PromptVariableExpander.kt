package com.aharou.feature.agent.domain.prompt

/** 只匹配原文中的标记；动态内容是数据，不再作为模板解析或跨来源去重。 */
internal object PromptVariableExpander {
    private val variables = Regex(
        listOf(
            PromptVars.SKILLS_VAR,
            PromptVars.MEMORY_VAR,
            PromptVars.SUBAGENTS_VAR,
            PromptVars.PROJECT_RULES_VAR,
            PromptVars.RULES_VAR,
            PromptVars.WORKSPACE_VAR,
            PromptVars.DATE_VAR
        ).joinToString("|") { Regex.escape(it) }
    )

    fun expand(
        text: String,
        skills: String?,
        memories: String?,
        subAgents: String?,
        projectRules: String?,
        workspace: String,
        date: String,
        rules: String? = null
    ): String {
        val blocks = mapOf(
            PromptVars.SKILLS_VAR to skills,
            PromptVars.MEMORY_VAR to memories,
            PromptVars.SUBAGENTS_VAR to subAgents,
            PromptVars.PROJECT_RULES_VAR to projectRules,
            PromptVars.RULES_VAR to rules
        )
        val expanded = mutableSetOf<String>()
        return variables.replace(text) { match ->
            when (val variable = match.value) {
                PromptVars.WORKSPACE_VAR -> workspace
                PromptVars.DATE_VAR -> date
                else -> if (expanded.add(variable)) blocks[variable].orEmpty() else ""
            }
        }
    }
}
