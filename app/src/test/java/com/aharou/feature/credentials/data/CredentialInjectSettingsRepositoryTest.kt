package com.aharou.feature.credentials.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CredentialInjectSettingsRepositoryTest {
    @Test fun revisionsInvalidateDisabledAndReenabledSnapshots() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("credential_inject_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = CredentialInjectSettingsRepository(context)
        val key = settings.serverKey("example.invalid", 22)

        settings.setAutoInject(key, true, markAsked = true)
        val enabled = settings.injectionState(key)
        assertTrue(enabled.enabled)
        assertTrue(settings.hasAsked(key))
        assertFalse(enabled.cleanupPending)

        settings.setAutoInject(key, false, cleanupPending = true)
        val disabled = settings.injectionState(key)
        assertFalse(disabled.enabled)
        assertTrue(disabled.cleanupPending)
        assertTrue(disabled.revision > enabled.revision)

        settings.setAutoInject(key, true)
        val reenabled = settings.injectionState(key)
        assertTrue(reenabled.enabled)
        assertFalse(reenabled.cleanupPending)
        assertTrue(reenabled.revision > disabled.revision)
        assertEquals(reenabled.revision, settings.injectionState(key).revision)
    }
}
