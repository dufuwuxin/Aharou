package com.aharou.feature.agent.domain.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class WorldbookAudience { MAIN, ROLE }

@Serializable
enum class WorldbookRelationType { RELATED, SUPPORTS, CONTRADICTS, SUPERSEDES }

@Serializable
data class WorldbookRelation(val target: String, val type: WorldbookRelationType = WorldbookRelationType.RELATED,
                             val weight: Double = 1.0) {
    init {
        require(target.isNotBlank()) { "Relation target must not be blank" }
        require(weight.isFinite() && weight in 0.0..8.0) { "Relation weight must be within 0..8" }
    }
}

/** User-authored metadata, not a factual confidence estimate or an authority grant. */
@Serializable
data class WorldbookRules(
    val enabled: Boolean = true,
    val triggers: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val matchAll: Boolean = false,
    val caseSensitive: Boolean = false,
    val priority: Int = 0,
    val roles: List<String> = emptyList(),
    val audiences: List<WorldbookAudience> = emptyList(),
    val confidence: Double? = null,
    val importance: Int? = null,
    val relations: List<WorldbookRelation> = emptyList(),
    val tags: List<String> = emptyList(),
    val keywordWeight: Double = 1.0,
    val tagWeight: Double = 1.0,
    val roleTags: List<String> = emptyList(),
    val ownerRole: String? = null,
    val sharedWithRoles: List<String> = emptyList(),
) {
    init {
        require((triggers + excludes + roles + tags + roleTags + sharedWithRoles).none { it.isBlank() }) { "Worldbook terms must not be blank" }
        require(ownerRole == null || ownerRole.isNotBlank()) { "Memory owner must not be blank" }
        require(confidence == null || (confidence.isFinite() && confidence in 0.0..1.0)) { "Confidence must be within 0..1" }
        require(importance == null || importance in 0..100) { "Importance must be within 0..100" }
        require(keywordWeight.isFinite() && keywordWeight in 0.0..8.0) { "Keyword weight must be within 0..8" }
        require(tagWeight.isFinite() && tagWeight in 0.0..8.0) { "Tag weight must be within 0..8" }
    }

    /** No history, model output or entry text is substituted for a missing current user input. */
    fun omission(userText: String, audience: WorldbookAudience, roleName: String?, roleTags: List<String> = emptyList()): String? = when {
        !enabled -> "worldbook disabled"
        ownerRole != null && (audience != WorldbookAudience.ROLE || roleName == null ||
            roleName != ownerRole && roleName !in sharedWithRoles) -> "worldbook: not owned or shared with this role"
        audiences.isNotEmpty() && audience !in audiences -> "worldbook: different audience"
        roles.isNotEmpty() && (audience != WorldbookAudience.ROLE || roleName !in roles) -> "worldbook: different or missing role"
        this.roleTags.isNotEmpty() && (audience != WorldbookAudience.ROLE || roleName == null ||
            this.roleTags.none { it in roleTags }) -> "worldbook: different or missing role tag"
        (triggers.isNotEmpty() || excludes.isNotEmpty()) && userText.isBlank() -> "worldbook: current user text unavailable"
        excludes.any { userText.contains(it, ignoreCase = !caseSensitive) } -> "worldbook: exclusion matched"
        triggers.isNotEmpty() && !(if (matchAll) triggers.all { userText.contains(it, ignoreCase = !caseSensitive) }
            else triggers.any { userText.contains(it, ignoreCase = !caseSensitive) }) -> "worldbook: trigger not matched"
        else -> null
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    fun explanation(): String = buildString {
        append("priority=$priority; confidence=${confidence ?: "unknown"}; importance=${importance ?: "unknown"}; user-authored")
        append("; keywordWeight=$keywordWeight; tagWeight=$tagWeight; tags=${tags.joinToString()}")
        append("; roleTags=${roleTags.joinToString()}")
        append("; ownerRole=${ownerRole ?: "shared"}; sharedWithRoles=${sharedWithRoles.joinToString()}")
        relations.forEach { append("; ${it.type.name}->${it.target}; weight=${it.weight}") }
    }

    companion object {
        private val json = Json { encodeDefaults = true }
        fun fromJson(value: String): WorldbookRules = json.decodeFromString(serializer(), value)
    }
}
