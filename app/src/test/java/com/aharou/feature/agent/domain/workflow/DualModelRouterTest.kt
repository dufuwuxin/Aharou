package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.repository.DualModelSettingsRepository
import com.aharou.feature.agent.domain.session.DualModelSettings
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.FallbackTarget
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DualModelRouterTest {
    private val settings = mockk<DualModelSettingsRepository>()
    private val sessions = mockk<SessionUseCase>()
    private val providers = mockk<AIProviderRepository>()
    private val router = DualModelRouter(settings, sessions, providers)
    private val choices = DualModelSettings(true, FallbackTarget("p", "plan"), FallbackTarget("p", "exec"))
    private fun session(mode: String = "PLAN", parent: String? = null) =
        ChatSessionEntity("s", "session", 0, 0, mode = mode, parentId = parent)
    private fun provider() = AIProviderConfig("p", "provider", ProviderType.OPENAI, "",
        baseUrl = "https://example.invalid", defaultModel = "default", models = listOf("plan", "exec"))
    private fun configured() {
        coEvery { sessions.getSessionById("s") } returns session()
        coEvery { settings.get("s") } returns choices
        coEvery { providers.getProviderById("p") } returns provider()
    }

    @Test fun noSessionDoesNotReadSettings() = runTest {
        assertNull(router.resolve(null))
        assertNull(router.resolve(""))
        coVerify(exactly = 0) { settings.get(any()) }
    }
    @Test fun childNeverInheritsOrReadsParentSwitch() = runTest {
        coEvery { sessions.getSessionById("s") } returns session(parent = "parent")
        assertNull(router.resolve("s"))
        coVerify(exactly = 0) { settings.get(any()) }
        coVerify(exactly = 0) { sessions.getSessionById("parent") }
    }
    @Test fun disabledDoesNotResolveProvider() = runTest {
        configured()
        coEvery { settings.get("s") } returns DualModelSettings()
        assertNull(router.resolve("s"))
        coVerify(exactly = 0) { providers.getProviderById(any()) }
    }
    @Test fun readsDatabaseModeOnEveryRequestInSameTurn() = runTest {
        configured()
        assertEquals("plan", router.resolve("s")?.effectiveModel)
        coEvery { sessions.getSessionById("s") } returns session("AUTO")
        assertEquals("exec", router.resolve("s")?.effectiveModel)
        coEvery { sessions.getSessionById("s") } returns session("PLAN")
        assertEquals("plan", router.resolve("s")?.effectiveModel)
    }
    @Test fun unavailableSelectionNeverUsesAnotherProvider() = runTest {
        configured()
        for (unavailable in listOf(null, provider().copy(isEnabled = false), provider().copy(baseUrl = ""),
            provider().copy(models = listOf("exec")))) {
            coEvery { providers.getProviderById("p") } returns unavailable
            assertTrue(runCatching { router.resolve("s") }.isFailure)
        }
        coVerify(exactly = 0) { providers.getProviderById("other") }
    }
    @Test fun invalidModeAndIncompleteSettingsFail() = runTest {
        configured()
        coEvery { settings.get("s") } returns choices.copy(execution = null)
        assertTrue(runCatching { router.resolve("s") }.isFailure)
        coEvery { settings.get("s") } returns choices
        coEvery { sessions.getSessionById("s") } returns session("unknown")
        assertTrue(runCatching { router.resolve("s") }.isFailure)
    }
    @Test fun freezesChoicesButStillReadsLatestMode() = runTest {
        configured()
        val frozen = router.settingsForTurn("s")
        coEvery { settings.get("s") } returns DualModelSettings()
        assertEquals("plan", router.resolve("s", frozen)?.effectiveModel)
        coEvery { sessions.getSessionById("s") } returns session("BUILD")
        assertEquals("exec", router.resolve("s", frozen)?.effectiveModel)
        assertNull(router.resolve("s"))
    }
    @Test fun fallbackSticksWithinStageButNeverLeaksAcrossStages() = runTest {
        configured()
        coEvery { providers.getProviderById("p") } returns provider().copy(
            fallbackChain = listOf(FallbackTarget("backup", "backup-model")))
        coEvery { providers.getProviderById("backup") } returns provider().copy(id = "backup")
        assertEquals("backup-model", router.next("s", choices)?.effectiveModel)
        assertEquals("backup-model", router.resolve("s", choices)?.effectiveModel)
        coEvery { sessions.getSessionById("s") } returns session("AUTO")
        assertEquals("exec", router.resolve("s", choices)?.effectiveModel)
        coEvery { sessions.getSessionById("s") } returns session("PLAN")
        assertEquals("backup-model", router.resolve("s", choices)?.effectiveModel)
        assertNull(router.next("s", choices))
        assertEquals("plan", router.resolve("s", choices)?.effectiveModel)
    }
    @Test fun missingSessionDoesNotCreateOrReadConfiguration() = runTest {
        coEvery { sessions.getSessionById("s") } returns null
        assertNull(router.resolve("s"))
        coVerify(exactly = 0) { settings.get(any()) }
    }
}
