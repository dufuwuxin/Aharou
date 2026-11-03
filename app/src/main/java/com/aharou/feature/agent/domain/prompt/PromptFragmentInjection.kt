package com.aharou.feature.agent.domain.prompt

/** 数字片段的注入策略；与成员自己的 InjectPart 及旧 keyword_injections 正交。 */
data class PromptFragmentInjection(
    val defaultInjection: Boolean = true,
    val keywords: List<String> = emptyList(),
    val members: Set<String> = emptySet(),
    val keywordsEnabled: Boolean = keywords.isNotEmpty(),
    val membersEnabled: Boolean = members.isNotEmpty()
) {
    fun matches(
        currentUserText: String,
        memberName: String? = null,
        inheritsMainRules: Boolean = false
    ): Boolean {
        val keywordMatch = keywordsEnabled && keywords.any { it.isNotBlank() && currentUserText.contains(it.trim(), ignoreCase = true) }
        return if (memberName == null) {
            defaultInjection || keywordMatch
        } else {
            (membersEnabled && memberName in members) || keywordMatch || (inheritsMainRules && defaultInjection)
        }
    }
}
