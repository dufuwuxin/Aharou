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
    }
    private val providers = mockk<AIProviderRepository>()
    private val defaults = DefaultModelSettingsRepository(ApplicationProvider.getApplicationContext<Context>(), groups)
    private val child = ChatSessionEntity("child", "member", 1, 1, providerId = "b", model = "B", parentId = "parent")
    private val provider = AIProviderConfig("b", "B", ProviderType.OPENAI, "fixture", baseUrl = "https://example.invalid", defaultModel = "B", models = listOf("B"))

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
