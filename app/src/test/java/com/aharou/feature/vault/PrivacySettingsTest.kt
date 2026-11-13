package com.aharou.feature.vault

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PrivacySettingsTest {
    @Test fun settingsPersistAndActuallyChangeTheSharedRedactionConsumer() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aharou_vault", Context.MODE_PRIVATE).edit().clear().commit()
        val previous = VaultRedactor.delegate
        try {
            val repository = VaultRepository(context)
            val sample = "test@example.org phone=13812345678"
            assertFalse(repository.privacyRedaction.value)
            assertEquals(sample, repository.redact(sample))
            assertTrue(repository.setPrivacyRedaction(true))
            assertFalse(VaultRedactor.redact(sample).contains("test@example.org"))
            assertFalse(VaultRedactor.redact(sample).contains("13812345678"))
            val reloaded = VaultRepository(context)
            assertTrue(reloaded.privacyRedaction.value)
            assertTrue(reloaded.setPrivacyRedaction(false))
            assertEquals(sample, reloaded.redact(sample))
            assertFalse(reloaded.previewRedaction(sample).contains("test@example.org"))
        } finally {
            VaultRedactor.delegate = previous
            context.getSharedPreferences("aharou_vault", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
