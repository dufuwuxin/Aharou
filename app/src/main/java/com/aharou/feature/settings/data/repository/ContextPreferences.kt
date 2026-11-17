package com.aharou.feature.settings.data.repository

/** The persisted values, not a separately stored preset id, are the source of truth. */
data class ContextPreferences(
    val rounds: Int = 0,
    val soft: Int = 55,
    val hard: Int = 90,
    val preserveSummary: Boolean = true,
    val keepTools: Int = 8,
) {
    val preset: ContextPreset
        get() = ContextPreset.entries.firstOrNull { it.preferences == this } ?: ContextPreset.CUSTOM
}

enum class ContextPreset(val id: String, val preferences: ContextPreferences?) {
    BALANCED("balanced", ContextPreferences()),
    ECONOMY("economy", ContextPreferences(8, 40, 75, true, 2)),
    DETAILED("detailed", ContextPreferences(0, 70, 95, true, 16)),
    CUSTOM("custom", null),
}
