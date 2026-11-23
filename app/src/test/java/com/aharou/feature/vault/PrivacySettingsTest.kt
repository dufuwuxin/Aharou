package com.aharou.feature.vault

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    @Test fun settingsPersistAndActuallyChangeTheSharedRedactionConsumer() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val vaultPrefs = context.getSharedPreferences("aharou_vault", Context.MODE_PRIVATE)
        val containerFile = context.preferencesDataStoreFile("container_prefs")
        vaultPrefs.edit().clear().commit()
        containerFile.delete()
        vaultPrefs.edit().putBoolean("privacy_redaction", true).commit()
        val previous = VaultRedactor.delegate
        val previousSnapshot = VaultRedactor.snapshotDelegate
        try {
            val container = ContainerSettingsRepository(context)
            container.migrateLegacyPrivacyRedaction()
            val repository = VaultRepository(context, container)
            val sample = "test@example.org phone=13812345678"
            container.setRedactEnvOutput(true)
            assertTrue(repository.privacyRedaction.first())
            assertFalse(repository.redact(sample).contains("test@example.org"))
            assertFalse(repository.redact(sample).contains("13812345678"))
            val reloaded = VaultRepository(context, container)
            assertTrue(reloaded.privacyRedaction.first())
            val capturedRedaction = container.redactEnvOutputEnabledSnapshot()
            container.setRedactEnvOutput(false)
            assertFalse(reloaded.privacyRedaction.first())
            assertEquals(sample, reloaded.redact(sample))
            assertFalse(VaultRedactor.redact(sample, capturedRedaction).contains("test@example.org"))
            assertFalse(VaultRedactor.redact(sample, capturedRedaction).contains("13812345678"))
            assertFalse(reloaded.previewRedaction(sample).contains("test@example.org"))
            assertFalse(vaultPrefs.contains("privacy_redaction"))
        } finally {
            VaultRedactor.delegate = previous
            VaultRedactor.snapshotDelegate = previousSnapshot
            vaultPrefs.edit().clear().commit()
            containerFile.delete()
        }
    }
}
