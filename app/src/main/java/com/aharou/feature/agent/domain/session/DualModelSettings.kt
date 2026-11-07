package com.aharou.feature.agent.domain.session

import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.settings.domain.model.FallbackTarget

data class DualModelSettings(
    val enabled: Boolean = false,
    val planning: FallbackTarget? = null,
    val execution: FallbackTarget? = null
) {
    val isConfigured: Boolean
        get() = listOf(planning, execution).all { it != null && it.providerId.isNotBlank() && it.model.isNotBlank() }

    fun target(mode: AgentMode): FallbackTarget? {
        if (!enabled) return null
        check(isConfigured) { "Dual model planning requires both planning and execution models" }
        return if (mode == AgentMode.PLAN) planning else execution
    }

    companion object {
        fun encodeTarget(target: FallbackTarget?): String {
            if (target == null) return ""
            require(target.providerId.isNotBlank() && ':' !in target.providerId && target.model.isNotBlank()) {
                "Expected providerId:model"
            }
            return "${target.providerId}:${target.model}"
        }

        fun decodeTarget(value: String?): FallbackTarget? {
            if (value == null || value.isEmpty()) return null
            val separator = value.indexOf(':')
            require(separator > 0 && separator < value.lastIndex) { "Expected providerId:model" }
            return FallbackTarget(value.substring(0, separator), value.substring(separator + 1)).also { encodeTarget(it) }
        }
    }
}
