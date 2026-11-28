package com.aharou.feature.agent.domain.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class McpClientPreferencesTest {
    @Test
    fun missingOrUnsupportedValueUsesTenMinuteDefault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("aharou_mcp_client", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val settings = McpClientPreferences(context)
        assertEquals(10, settings.idleTimeoutMinutes)
        prefs.edit().putInt("idle_timeout_minutes", 17).commit()
        assertEquals(10, McpClientPreferences(context).idleTimeoutMinutes)
    }

    @Test
    fun settingChoicePersistsAndRejectsUnsupportedValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("aharou_mcp_client", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = McpClientPreferences(context)

        McpClientPreferences.IDLE_TIMEOUT_CHOICES_MINUTES.forEach { minutes ->
            settings.idleTimeoutMinutes = minutes
            assertEquals(minutes, McpClientPreferences(context).idleTimeoutMinutes)
        }
        assertTrue(runCatching { settings.idleTimeoutMinutes = 2 }.isFailure)
    }
}
