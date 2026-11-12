package com.aharou.feature.agent.domain.workflow

data class ContextWatermarks(val warnTokens: Int, val maxTokens: Int) {
    fun exceeded(tokens: Int): Boolean = tokens >= maxTokens

    companion object {
        fun from(inputBudget: Int, warnPercent: Int, maxPercent: Int): ContextWatermarks {
            val budget = inputBudget.coerceAtLeast(1)
            val max = (budget.toLong() * maxPercent.coerceIn(1, 100) / 100).toInt().coerceAtLeast(1)
            val warn = (budget.toLong() * warnPercent.coerceIn(1, 100) / 100).toInt().coerceIn(1, max)
            return ContextWatermarks(warn, max)
        }
    }
}
