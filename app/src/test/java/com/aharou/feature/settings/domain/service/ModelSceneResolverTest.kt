package com.aharou.feature.settings.domain.service

import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.ModelRouteGroupDataException
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteMember
import com.aharou.feature.settings.domain.model.ModelScene
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.SceneBindingIssue
import com.aharou.feature.settings.domain.model.SceneResolution
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ModelSceneResolverTest {
    private val providers = mockk<AIProviderRepository>()
    private val defaults = mockk<DefaultModelSettingsRepository>()
    private val resolver = ModelSceneResolver(providers, defaults, mockk(), mockk(), mockk(), mockk(), mockk())
    private val group = ModelRouteGroup("g", "Group", listOf(ModelRouteMember("a", "A"), ModelRouteMember("b", "B")))
    private val a = AIProviderConfig("a", "Provider A", ProviderType.OPENAI, "fixture", baseUrl = "", defaultModel = "A", models = listOf("A"))
    private val b = a.copy(id = "b", name = "Provider B", defaultModel = "Other", selectedModel = "Other", models = listOf("B", "Other"))

    @Test fun mainChatRouteSkipsDisabledOrMissingFirstProvider() = runTest {
        coEvery { defaults.getDefaultProviderId() } returns group.selectionId
        coEvery { defaults.getRouteGroup(group.id) } returns group

        for (snapshot in listOf(listOf(b, a.copy(isEnabled = false)), listOf(b))) {
            every { providers.getAllProviders() } returns flowOf(snapshot)
            assertEquals(
                SceneResolution.Bound(ModelScene.MAIN_CHAT, "b", "Provider B", "B"),
                resolver.resolve(ModelScene.MAIN_CHAT),
            )
        }
        coVerify(exactly = 0) { defaults.getDefaultModel() }
    }

    @Test fun mainChatRouteReportsFailureWithoutUsingANonGroupModel() = runTest {
        val unrelated = a.copy(id = "unrelated")
        every { providers.getAllProviders() } returns flowOf(listOf(a.copy(apiKey = ""), b.copy(models = listOf("Other")), unrelated))
        coEvery { defaults.getDefaultProviderId() } returns group.selectionId
        coEvery { defaults.getRouteGroup(group.id) } returns group

        assertEquals(
            SceneResolution.Unavailable(ModelScene.MAIN_CHAT, SceneBindingIssue.RESOLVE_FAILED),
            resolver.resolve(ModelScene.MAIN_CHAT),
        )
        coVerify(exactly = 0) { defaults.getDefaultModel() }
    }

    @Test fun missingMainChatRouteIsUnavailable() = runTest {
        every { providers.getAllProviders() } returns flowOf(listOf(a))
        coEvery { defaults.getDefaultProviderId() } returns group.selectionId
        coEvery { defaults.getRouteGroup(group.id) } returns null

        assertEquals(
            SceneResolution.Unavailable(ModelScene.MAIN_CHAT, SceneBindingIssue.RESOLVE_FAILED),
            resolver.resolve(ModelScene.MAIN_CHAT),
        )
        coVerify(exactly = 0) { defaults.getDefaultModel() }
    }

    @Test fun corruptMainChatRouteIsUnavailable() = runTest {
        every { providers.getAllProviders() } returns flowOf(listOf(a))
        coEvery { defaults.getDefaultProviderId() } returns group.selectionId
        coEvery { defaults.getRouteGroup(group.id) } throws ModelRouteGroupDataException(IllegalArgumentException("broken JSON"))

        assertEquals(
            SceneResolution.Unavailable(ModelScene.MAIN_CHAT, SceneBindingIssue.RESOLVE_FAILED),
            resolver.resolve(ModelScene.MAIN_CHAT),
        )
        coVerify(exactly = 0) { defaults.getDefaultModel() }
    }

    @Test fun unconfiguredMainChatStillInherits() = runTest {
        every { providers.getAllProviders() } returns flowOf(listOf(a))
        coEvery { defaults.getDefaultProviderId() } returns ""
        coEvery { defaults.getDefaultModel() } returns ""

        assertEquals(
            SceneResolution.Inherited(ModelScene.MAIN_CHAT, SceneBindingIssue.NOT_CONFIGURED),
            resolver.resolve(ModelScene.MAIN_CHAT),
        )
        coVerify(exactly = 0) { defaults.getRouteGroup(any()) }
    }

    @Test fun ordinaryMissingProviderStillInherits() = runTest {
        every { providers.getAllProviders() } returns flowOf(listOf(a))
        coEvery { defaults.getDefaultProviderId() } returns "missing"
        coEvery { defaults.getDefaultModel() } returns "model"

        assertEquals(
            SceneResolution.Inherited(ModelScene.MAIN_CHAT, SceneBindingIssue.PROVIDER_MISSING),
            resolver.resolve(ModelScene.MAIN_CHAT),
        )
    }

    @Test fun mainChatSelectionReadCancellationPropagates() = runTest {
        val cancelled = CancellationException("selection read cancelled")
        every { providers.getAllProviders() } returns flowOf(listOf(a))
        coEvery { defaults.getDefaultProviderId() } throws cancelled

        try {
            resolver.resolve(ModelScene.MAIN_CHAT)
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test fun mainChatRouteReadCancellationPropagates() = runTest {
        val cancelled = CancellationException("route read cancelled")
        every { providers.getAllProviders() } returns flowOf(listOf(a))
        coEvery { defaults.getDefaultProviderId() } returns group.selectionId
        coEvery { defaults.getRouteGroup(group.id) } throws cancelled

        try {
            resolver.resolve(ModelScene.MAIN_CHAT)
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        coVerify(exactly = 0) { defaults.getDefaultModel() }
    }

    @Test fun providerReadCancellationPropagatesForSingleAndAllScenes() = runTest {
        val cancelled = CancellationException("provider read cancelled")
        every { providers.getAllProviders() } returns flow<List<AIProviderConfig>> { throw cancelled }
        val operations: List<suspend () -> Any> = listOf(
            { resolver.resolve(ModelScene.MAIN_CHAT) },
            { resolver.resolveAll() },
        )

        for (operation in operations) {
            try {
                operation()
                fail("Provider read cancellation must propagate")
            } catch (actual: CancellationException) {
                assertSame(cancelled, actual)
            }
        }
        coVerify(exactly = 0) { defaults.getDefaultProviderId() }
    }

    @Test fun ordinaryProviderReadFailureKeepsExistingFallback() = runTest {
        every { providers.getAllProviders() } returns flow<List<AIProviderConfig>> {
            throw IllegalStateException("provider read failed")
        }
        assertEquals(
            SceneResolution.Inherited(ModelScene.MAIN_CHAT, SceneBindingIssue.RESOLVE_FAILED),
            resolver.resolve(ModelScene.MAIN_CHAT),
        )
        val expected = ModelScene.entries.associateWith { scene ->
            if (scene.fallsBackToMainChat) {
                SceneResolution.Inherited(scene, SceneBindingIssue.RESOLVE_FAILED)
            } else {
                SceneResolution.Unavailable(scene, SceneBindingIssue.RESOLVE_FAILED)
            }
        }
        assertEquals(expected, resolver.resolveAll())
    }
}
