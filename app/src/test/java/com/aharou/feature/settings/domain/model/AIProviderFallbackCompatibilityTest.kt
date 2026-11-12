package com.aharou.feature.settings.domain.model

import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.settings.data.local.entity.AIProviderEntity
import com.aharou.feature.settings.data.repository.AIProviderRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AIProviderFallbackCompatibilityTest {
    private fun legacy() = AIProviderConfig("primary", "Primary", ProviderType.OPENAI, "", baseUrl = "", defaultModel = "m",
        fallbackProviderId = "backup", fallbackModel = "old-model")

    @Test fun unrelatedSavePreservesLegacyFallbackThroughRepositoryMapping() {
        mockkObject(KeystoreCipher)
        try {
            every { KeystoreCipher.encryptString(any()) } answers { firstArg() }
            every { KeystoreCipher.decryptString(any()) } answers { firstArg() }
            val accounts = mockk<com.aharou.feature.settings.data.account.AccountProviderManager>()
            every { accounts.normalizeConfig(any()) } answers { firstArg() }
            val repo = AIProviderRepositoryImpl(mockk(), mockk(), accounts)
            val toEntity = AIProviderRepositoryImpl::class.java.getDeclaredMethod("toEntity", AIProviderConfig::class.java)
                .apply { isAccessible = true }
            val toDomain = AIProviderRepositoryImpl::class.java.getDeclaredMethod("toDomainModel", AIProviderEntity::class.java)
                .apply { isAccessible = true }
            val entity = toEntity.invoke(repo, legacy().copy(name = "Renamed").sanitized()) as AIProviderEntity
            val restored = toDomain.invoke(repo, entity) as AIProviderConfig
            assertEquals("backup", entity.fallbackProviderId)
            assertEquals("old-model", entity.fallbackModel)
            assertEquals(listOf(FallbackTarget("backup", "old-model")), restored.fallbackChain)
        } finally { unmockkObject(KeystoreCipher) }
    }

    @Test fun oldWritersUpdateHeadAndPreserveTail() {
        val config = legacy().withFallbackChain(listOf(FallbackTarget("a", "m1"), FallbackTarget("b", "m2")))
        val changed = config.withLegacyFallback(providerId = "c").withLegacyFallback(model = "m3")
        assertEquals(listOf(FallbackTarget("c", "m3"), FallbackTarget("b", "m2")), changed.effectiveFallbackChain)
        assertEquals("c", changed.fallbackProviderId)
        assertEquals("m3", changed.fallbackModel)
    }

    @Test fun legacyModelCanBeSetBeforeProvider() {
        val changed = legacy().withFallbackChain(emptyList())
            .withLegacyFallback(model = "first-model").withLegacyFallback(providerId = "new")
        assertEquals(listOf(FallbackTarget("new", "first-model")), changed.effectiveFallbackChain)
    }

    @Test fun clearingChainOrLegacyProviderDoesNotResurrectFallback() {
        assertEquals(emptyList<FallbackTarget>(), legacy().withFallbackChain(emptyList()).sanitized().effectiveFallbackChain)
        assertEquals(emptyList<FallbackTarget>(), legacy().withLegacyFallback(providerId = "").sanitized().effectiveFallbackChain)
    }
}
