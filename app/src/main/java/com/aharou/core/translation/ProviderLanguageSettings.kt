package com.aharou.core.translation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderLanguageSettings @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("provider_languages", Context.MODE_PRIVATE)

    fun get(providerId: String): ProviderLanguagePolicy = runCatching {
        ProviderLanguagePolicy.fromWire(preferences.getString(providerId, "native") ?: "native")
    }.getOrDefault(ProviderLanguagePolicy.NATIVE)

    fun set(providerId: String, policy: ProviderLanguagePolicy) {
        require(providerId.isNotBlank())
        preferences.edit().putString(providerId, policy.wireValue).apply()
    }

    fun remove(providerId: String) {
        preferences.edit().remove(providerId).apply()
    }
}
