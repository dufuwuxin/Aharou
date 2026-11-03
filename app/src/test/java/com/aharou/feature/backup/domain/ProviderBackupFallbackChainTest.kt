package com.aharou.feature.backup.domain

import android.content.Context
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.backup.data.BackupManagerImpl
import com.aharou.feature.settings.data.local.entity.AIProviderEntity
import com.aharou.feature.settings.domain.model.FallbackTarget
import com.aharou.feature.settings.domain.model.parseFallbackChain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderBackupFallbackChainTest {
    @get:Rule val folder = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun multipleHopsSurviveActualExportSerializationAndImportMapping() {
        mockkObject(KeystoreCipher)
        try {
            every { KeystoreCipher.encryptString(any()) } answers { firstArg() }
            every { KeystoreCipher.decryptString(any()) } answers { firstArg() }
            val manager = createManager()
            val export = BackupManagerImpl::class.java.getDeclaredMethod("toDto", AIProviderEntity::class.java)
                .apply { isAccessible = true }
            val source = AIProviderEntity(id = "p", name = "P", type = "OPENAI", apiKey = "", baseUrl = "", defaultModel = "m",
                fallbackProviderId = "a", fallbackModel = "m1", fallbackChain = "a|m1\nb|m2")
            val dto = export.invoke(manager, source) as ProviderDto
            val restored = import(manager, json.decodeFromString(ProviderDto.serializer(), json.encodeToString(ProviderDto.serializer(), dto)))
            assertEquals(listOf(FallbackTarget("a", "m1"), FallbackTarget("b", "m2")), parseFallbackChain(restored.fallbackChain))
            assertEquals(source.fallbackProviderId, restored.fallbackProviderId)
            assertEquals(source.fallbackModel, restored.fallbackModel)
        } finally { unmockkObject(KeystoreCipher) }
    }

    @Test fun oldBackupDefaultsRemainCompatible() {
        val dto = json.decodeFromString(ProviderDto.serializer(),
            """{"id":"p","name":"P","type":"OPENAI","apiKey":"","baseUrl":"","defaultModel":"m"}""")
        assertNull(dto.fallbackChain)
        mockkObject(KeystoreCipher)
        try {
            every { KeystoreCipher.encryptString(any()) } answers { firstArg() }
            val restored = import(createManager(), dto)
            assertEquals("", restored.fallbackChain)
            assertEquals("", restored.fallbackProviderId)
            assertEquals("", restored.fallbackModel)
        } finally { unmockkObject(KeystoreCipher) }
    }

    // Only collaborators are mocked; reflection must invoke the real private mapping methods.
    private fun createManager(): BackupManagerImpl {
        val context = mockk<Context>()
        every { context.cacheDir } returns folder.root
        return BackupManagerImpl(
            context = context,
            aiProviderDao = mockk(),
            remoteConnectionDao = mockk(),
            chatSessionDao = mockk(),
            agentMessageDao = mockk(),
            messageArchiveStore = mockk(),
            todoItemDao = mockk(),
            mcpConfigRepository = mockk(),
            mcpManager = mockk(),
            permissionRulesRepository = mockk(),
            themeSettingsRepository = mockk(),
            keepaliveSettingsRepository = mockk(),
            screenOnSettingsRepository = mockk(),
            agentSoundSettingsRepository = mockk(),
            generalSettingsRepository = mockk(),
            logSettingsRepository = mockk(),
            visionModelSettingsRepository = mockk(),
            compactionModelSettingsRepository = mockk(),
            syncSettingsRepository = mockk(),
            workspaceRepository = mockk()
        )
    }

    private fun import(manager: BackupManagerImpl, dto: ProviderDto): AIProviderEntity =
        BackupManagerImpl::class.java.getDeclaredMethod("toEntity", ProviderDto::class.java)
            .apply { isAccessible = true }.invoke(manager, dto) as AIProviderEntity
}
