package com.aharou.feature.agent.domain.mcp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Device-local MCP client options; deliberately separate from shareable mcp.json server configuration. */
@Singleton
class McpClientPreferences @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Idle time before an unused stdio connection is stopped. Old installs default to ten minutes. */
    var idleTimeoutMinutes: Int
        get() = normalize(prefs.getInt(KEY_IDLE_TIMEOUT_MINUTES, DEFAULT_IDLE_TIMEOUT_MINUTES))
        set(value) {
            require(value in IDLE_TIMEOUT_CHOICES_MINUTES) { "Unsupported MCP idle timeout: $value" }
            prefs.edit().putInt(KEY_IDLE_TIMEOUT_MINUTES, value).apply()
        }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MINUTES = 10
        val IDLE_TIMEOUT_CHOICES_MINUTES = listOf(1, 3, 5, 10, 30, 60)

        internal fun normalize(value: Int): Int =
            value.takeIf { it in IDLE_TIMEOUT_CHOICES_MINUTES } ?: DEFAULT_IDLE_TIMEOUT_MINUTES

        private const val PREFERENCES = "aharou_mcp_client"
        private const val KEY_IDLE_TIMEOUT_MINUTES = "idle_timeout_minutes"
    }
}
