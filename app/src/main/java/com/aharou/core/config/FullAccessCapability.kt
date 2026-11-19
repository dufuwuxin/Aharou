package com.aharou.core.config

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object FullAccessCapability {
    private val state = MutableStateFlow(false)
    val enabled = state.asStateFlow()
    private var preferences: android.content.SharedPreferences? = null
    private val revocations = mutableListOf<() -> Unit>()
    @Volatile private var revision = 0L

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        preferences = context.applicationContext.getSharedPreferences("host_full_access", Context.MODE_PRIVATE)
        state.value = preferences?.getBoolean("enabled", false) == true
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        val store = checkNotNull(preferences) { "Full access is not initialized" }
        if (!value) {
            revision++
            state.value = false
            revocations.forEach { it() }
        }
        check(store.edit().putBoolean("enabled", value).commit()) { "Permission could not be saved" }
        if (value) { revision++; state.value = true }
    }

    fun currentRevision(): Long = revision
    fun isGranted(expected: Long): Boolean = state.value && revision == expected

    @Synchronized
    fun onRevoke(listener: () -> Unit) { revocations += listener }
}
