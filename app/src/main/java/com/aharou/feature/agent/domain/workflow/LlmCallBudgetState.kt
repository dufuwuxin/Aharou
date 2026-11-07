package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.session.DualModelSettings

internal class LlmCallBudgetState(
    val routingSettings: DualModelSettings,
    providerKey: String,
    var baselineEstimate: Int,
    var baselineUsage: Int
) {
    private data class Recovery(var compactionFailed: Boolean = false, var overflowAttempted: Boolean = false)
    private val recoveries = mutableMapOf<String, Recovery>()
    private var providerKey = providerKey
    private val recovery: Recovery get() = recoveries.getOrPut(providerKey) { Recovery() }

    var compactionAttemptFailed: Boolean
        get() = recovery.compactionFailed
        set(value) { recovery.compactionFailed = value }
    var overflowRecoveryAttempted: Boolean
        get() = recovery.overflowAttempted
        set(value) { recovery.overflowAttempted = value }

    fun selectProvider(key: String) {
        if (key == providerKey) return
        baselineEstimate = 0
        baselineUsage = 0
        providerKey = key
    }
}
