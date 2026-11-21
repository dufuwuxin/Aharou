package com.aharou.feature.settings.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ProviderLibraryPreferences {
    private var preferences: android.content.SharedPreferences? = null
    private val state = MutableStateFlow<Set<String>>(emptySet())
    val hiddenTypes = state.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("provider_library", Context.MODE_PRIVATE)
        state.value = preferences?.getStringSet("hidden_types", emptySet()).orEmpty().toSet()
    }

    @Synchronized
    internal fun setBrandHidden(brand: String, hidden: Boolean) {
        val current = state.value
        val updated = if (hidden) current + brand else current - brand
        if (updated != current) setHidden(updated)
    }

    @Synchronized
    fun setHidden(types: Set<String>) {
        require(types.all { it.matches(Regex("[a-z0-9_-]+")) })
        check(checkNotNull(preferences).edit().putStringSet("hidden_types", types).commit())
        state.value = types.toSet()
    }
}
