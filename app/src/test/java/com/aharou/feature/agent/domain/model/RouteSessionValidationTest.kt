package com.aharou.feature.agent.domain.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.workflow.WorkspaceModelChain
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.ModelRouteGroupRepository
import com.aharou.feature.settings.data.repository.WorkspaceModelChainRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.FallbackTarget
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteMember
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class RouteSessionValidationTest {
    private val groups = mockk<ModelRouteGroupRepository> {
        every { groups } returns kotlinx.coroutines.flow.flowOf(emptyList())
        every { readState } returns kotlinx.coroutines.flow.flowOf(Result.success(emptyList()))
    }
    private val providers = mockk<AIProviderRepository>()
    private val defaults = DefaultModelSettingsRepository(ApplicationProvider.getApplicationContext<Context>(), groups)
    private val child = ChatSessionEntity("child", "member", 1, 1, providerId = "b", model = "B", parentId = "parent")
    private val provider = AIProviderConfig("b", "B", ProviderType.OPENAI, "fixture", baseUrl = "https://example.invalid", defaultModel = "B", models = listOf("B"))

    @Test fun routeSkipsDisabledOrMissingFirstProvider() = runTest {
        val group = ModelRouteGroup("g", "Group", listOf(ModelRouteMember("a", "A"), ModelRouteMember("b", "B")))
        val main = child.copy(id = "main", parentId = null, providerId = group.selectionId, model = "g")
        coEvery { groups.get(group.id) } returns group
        coEvery { providers.getProviderById("a") } returns provider.copy(id = "a", models = listOf("A"), isEnabled = false)
        coEvery { providers.getProviderById("b") } returns provider.copy(selectedModel = "Other")

        for (firstProviderMissing in listOf(false, true)) {
            if (firstProviderMissing) coEvery { providers.getProviderById("a") } returns null
            val selected = requireNotNull(defaults.resolveRouting(main, providers))
            assertEquals("b", selected.id)
            assertEquals("B", selected.effectiveModel)
        }
    }

    @Test fun routeReportsUnavailableWhenNoMemberCanBeUsed() = runTest {
        val group = ModelRouteGroup("g", "Group", listOf(ModelRouteMember("a", "A"), ModelRouteMember("b", "B")))
        coEvery { groups.get(group.id) } returns group
        coEvery { providers.getProviderById("a") } returns null
        coEvery { providers.getProviderById("b") } returns provider.copy(models = listOf("Other"))

        val failure = runCatching { defaults.resolveRoute(group.selectionId, providers) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("MODEL_ROUTE_MODEL_UNAVAILABLE", failure?.message)
    }

    @Test fun removedGroupKeepsItsDistinctError() = runTest {
        coEvery { groups.get("removed") } returns null

        val failure = runCatching { defaults.resolveRoute("route:removed", providers) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("MODEL_ROUTE_GROUP_REMOVED", failure?.message)
        coVerify(exactly = 0) { providers.getProviderById(any()) }
    }

    @Test fun allocatedMemberKeepsItsModelWhenDefaultGroupChanges() = runTest {
        coEvery { groups.isAllocatedMember("child") } returns true
        coEvery { providers.getProviderById("b") } returns provider.copy(selectedModel = "Other")
        val first = ModelRouteGroup("g1", "First", listOf(ModelRouteMember("a", "A")))
        val second = ModelRouteGroup("g2", "Second", listOf(ModelRouteMember("c", "C")))
        try {
            for (group in listOf(first, second)) {
                defaults.setDefaultModel(group.selectionId, group.id)
                val selected = requireNotNull(defaults.resolveRouting(child, providers))
                assertEquals("b", selected.id)
                assertEquals("B", selected.effectiveModel)
            }
            coVerify(exactly = 0) { groups.get(any()) }
        } finally {
            defaults.clear()
        }
    }

    @Test fun persistedRouteMemberRefusesSilentFallbackWhenItsConcreteModelDisappears() = runTest {
        coEvery { groups.isAllocatedMember("child") } returns true
        coEvery { providers.getProviderById("b") } returns provider
        assertEquals("B", defaults.resolveRouting(child, providers)?.effectiveModel)
        coVerify(exactly = 1) { providers.getProviderById("b") }
        coEvery { providers.getProviderById("b") } returns provider.copy(models = emptyList())
        assertTrue(runCatching { defaults.resolveRouting(child, providers) }.isFailure)
        coEvery { providers.getProviderById("b") } returns null
        assertTrue(runCatching { defaults.resolveRouting(child, providers) }.isFailure)
    }

    @Test fun ordinaryHistoricalMembersKeepTheirExistingCompatibilityFallback() = runTest {
        coEvery { groups.isAllocatedMember("child") } returns false
        defaults.resolveRouting(child, providers)
        coVerify(exactly = 0) { providers.getProviderById(any()) }
    }

    @Test fun explicitRouteSelectionDisablesAnOlderWorkspaceChainForOnlyThatSession() = runTest {
        val settings = mockk<WorkspaceModelChainRepository>()
        val selections = mockk<DefaultModelSettingsRepository>()
        val sessions = mockk<SessionUseCase>()
        val main = child.copy(id = "main", parentId = null, providerId = "route:g", model = "g")
        coEvery { sessions.getSessionById("main") } returns main
        coEvery { selections.isRouteSelection(main) } returns true
        val chain = WorkspaceModelChain(settings, providers).apply { defaultModels = selections; this.sessions = sessions }
        assertNull(chain.begin("/project", "main", true))
        assertNull(chain.current("main"))
        coVerify(exactly = 0) { settings.get(any()) }
    }
}
