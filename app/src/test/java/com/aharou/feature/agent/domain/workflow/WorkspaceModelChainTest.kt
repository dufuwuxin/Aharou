package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.settings.data.repository.WorkspaceModelChainRepository
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

class WorkspaceModelChainTest {
    private val settings = mockk<WorkspaceModelChainRepository>()
    private val providers = mockk<AIProviderRepository>()
    private val router = WorkspaceModelChain(settings, providers)
    private fun provider(id: String) = AIProviderConfig(id = id, name = id, type = ProviderType.OPENAI,
        apiKey = "test-key", baseUrl = "https://example.invalid", defaultModel = "default")

    @Test fun followsOrderIncludingModelsOfSameProviderAndStopsAtEnd() = runTest {
        coEvery { settings.get("/one") } returns listOf(FallbackTarget("a", "one"), FallbackTarget("a", "two"), FallbackTarget("b", "three"))
        coEvery { providers.getProviderById(any()) } answers { provider(firstArg()) }
        assertEquals("one", router.begin("/one", "s", true)?.effectiveModel)
        assertEquals("two", router.next("s")?.effectiveModel)
        assertEquals("two", router.begin("/one", "s", true)?.effectiveModel)
        assertEquals("three", router.next("s")?.effectiveModel)
        assertNull(router.next("s"))
        assertNull(router.next("s"))
        assertTrue(router.configured("s"))
        assertTrue(runCatching { router.current("s") }.isFailure)
        assertEquals("one", router.begin("/one", "s", true)?.effectiveModel)
    }

    @Test fun isolatesWorkspacesAndResetsAfterEditingConfiguration() = runTest {
        coEvery { settings.get("/one") } returns listOf(FallbackTarget("a", "one"), FallbackTarget("a", "two"))
        coEvery { settings.get("/two") } returns listOf(FallbackTarget("b", "three"))
        coEvery { providers.getProviderById(any()) } answers { provider(firstArg()) }
        router.begin("/one", "s", true)
        router.next("s")
        assertEquals("one", router.begin("/one", "other", true)?.effectiveModel)
        assertEquals("three", router.begin("/two", "s", true)?.effectiveModel)
        coEvery { settings.get("/two") } returns emptyList()
        assertNull(router.begin("/two", "s", true))
        assertFalse(router.configured("s"))
    }

    @Test fun skipsMissingCandidatesAndNeverOverridesSubagentModels() = runTest {
        assertNull(router.begin("/one", "child", false))
        coVerify(exactly = 0) { settings.get(any()) }
        coEvery { settings.get("/one") } returns listOf(FallbackTarget("missing", "one"), FallbackTarget("b", "two"))
        coEvery { providers.getProviderById("missing") } returns null
        coEvery { providers.getProviderById("b") } returns provider("b")
        assertEquals("two", router.begin("/one", "s", true)?.effectiveModel)
        coEvery { providers.getProviderById("b") } returns null
        assertTrue(runCatching { router.begin("/one", "s", true) }.isFailure)
    }
}
