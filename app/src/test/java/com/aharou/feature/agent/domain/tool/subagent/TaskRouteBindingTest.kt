package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.model.RouteGroupAllocation
import com.aharou.feature.agent.domain.model.RouteModelBinding
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.ModelRouteGroupDataException
import com.aharou.feature.settings.data.repository.ModelRouteGroupRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteMember
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TaskRouteBindingTest {
    private val group = ModelRouteGroup("g", "Ordered", listOf(ModelRouteMember("a", "A"), ModelRouteMember("b", "B"), ModelRouteMember("c", "C")))
    private val groups = mockk<ModelRouteGroupRepository>()
    private val defaults = mockk<DefaultModelSettingsRepository>()
    private val providers = mockk<AIProviderRepository>()
    private val allocation = RouteGroupAllocation()
    private val resolver = TaskRouteBinding(groups, defaults, providers, allocation)
    private fun parent(id: String = "main", provider: String? = "route:g") = ChatSessionEntity(id = id, title = "main", workspacePath = "/project", createdAt = 1, updatedAt = 1, providerId = provider, model = "g")
    private fun prepare() {
        val configs = group.models.map { AIProviderConfig(it.providerId, it.providerId, ProviderType.OPENAI, "fixture", baseUrl = "https://example.invalid", defaultModel = it.model, models = listOf(it.model)) }
        coEvery { groups.get("g") } returns group
        coEvery { providers.getProviderById(any()) } answers { configs.firstOrNull { it.id == firstArg<String>() } }
        every { providers.getAllProviders() } returns flowOf(configs)
        coEvery { defaults.getDefaultProviderId() } returns "route:g"
    }

    @Test fun rejectedOrFailedCreationDoesNotConsumeAGroupPosition() = runTest {
        prepare()
        assertEquals(RouteModelBinding("b", "B"), resolver.prepare(parent(), null, null).binding)
        val accepted = resolver.prepare(parent(), null, null)
        assertEquals(RouteModelBinding("b", "B"), accepted.binding)
        resolver.committed(accepted)
        val next = resolver.prepare(parent(), null, null)
        assertEquals(RouteModelBinding("c", "C"), next.binding)
        resolver.committed(next)
        assertEquals(RouteModelBinding("a", "A"), resolver.prepare(parent(), null, null).binding)
        assertEquals(RouteModelBinding("b", "B"), resolver.prepare(parent("other"), null, null).binding)
    }

    @Test fun fixedRoleOnlyModelAndProviderResolveConcreteBindingsWithoutSpendingSlots() = runTest {
        prepare()
        val fixed = resolver.prepare(parent(), null, "C")
        assertEquals(RouteModelBinding("c", "C"), fixed.binding)
        resolver.committed(fixed)
        assertEquals(RouteModelBinding("b", "B"), resolver.prepare(parent(), "b", null).binding)
        assertEquals(RouteModelBinding("b", "B"), resolver.prepare(parent(), null, null).binding)
    }

    @Test fun parentFollowingTheDefaultGroupUsesItsOwnAllocation() = runTest {
        prepare()
        assertEquals(RouteModelBinding("b", "B"), resolver.prepare(parent(provider = null), null, null).binding)
    }

    @Test fun failedSessionWriteRemovesItsPersistentRouteMarkerAndDoesNotAdvance() = runTest {
        prepare()
        val marks = mutableListOf<Boolean>()
        coEvery { groups.markMember("new-child", any()) } answers { marks += secondArg<Boolean>() }
        val selected = resolver.prepare(parent(), null, null)
        val child = parent("new-child").copy(parentId = "main", providerId = selected.binding.providerId, model = selected.binding.model)
        assertTrue(runCatching { resolver.saveMember(child, selected) { error("injected DAO failure") } }.isFailure)
        assertEquals(listOf(true, false), marks)
        assertEquals(RouteModelBinding("b", "B"), resolver.prepare(parent(), null, null).binding)
    }

    @Test fun corruptGroupStorageFailsWithoutFallbackOrSpendingAnOrderedSlot() = runTest {
        prepare()
        val failure = ModelRouteGroupDataException(IllegalArgumentException("invalid JSON"))
        coEvery { groups.get("g") } throws failure
        assertSame(failure, runCatching { resolver.prepare(parent(), null, null) }.exceptionOrNull())
        coVerify(exactly = 0) { providers.getProviderById(any()) }
        coEvery { groups.get("g") } returns group
        assertEquals(RouteModelBinding("b", "B"), resolver.prepare(parent(), null, null).binding)
    }

    @Test fun missingGroupOrUnavailableModelFailsBeforeAConcreteMemberIsCreated() = runTest {
        prepare()
        coEvery { groups.get("g") } returns null
        assertTrue(runCatching { resolver.prepare(parent(), null, null) }.isFailure)
        coEvery { groups.get("g") } returns group
        coEvery { providers.getProviderById("b") } returns null
        assertTrue(runCatching { resolver.prepare(parent(), null, null) }.isFailure)
    }
}
