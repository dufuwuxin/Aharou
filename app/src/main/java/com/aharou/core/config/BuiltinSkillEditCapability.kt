package com.aharou.core.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-only permission; restart revokes permission without deleting saved overrides. */
object BuiltinSkillEditCapability {
    private val state = MutableStateFlow(false)
    val enabled = state.asStateFlow()
    @Synchronized fun setEnabled(enabled: Boolean) { state.value = enabled }
}
