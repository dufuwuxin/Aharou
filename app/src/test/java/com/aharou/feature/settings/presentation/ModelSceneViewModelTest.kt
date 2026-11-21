package com.aharou.feature.settings.presentation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import com.aharou.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aharou.feature.settings.data.repository.MemoryModelSettingsRepository
import com.aharou.feature.settings.data.repository.ModelRouteGroupDataException
import com.aharou.feature.settings.data.repository.ModelRouteGroupRepository
import com.aharou.feature.settings.data.repository.TitleModelSettingsRepository
import com.aharou.feature.settings.data.repository.VisionModelSettingsRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteGroupCodec
import com.aharou.feature.settings.domain.model.ModelRouteMember
import com.aharou.feature.settings.domain.model.ModelScene
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.SceneBindingIssue
import com.aharou.feature.settings.domain.model.SceneResolution
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.settings.domain.service.ModelSceneResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ModelSceneViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun corruptRouteInvalidationReplacesBoundPreviewAndRecoveryRestoresBound() = runTest(dispatcher) {
        val group = ModelRouteGroup("g", "Group", listOf(ModelRouteMember("provider", "model")))
        val groupsKey = stringPreferencesKey("groups_json")
        val valid = preferencesOf(groupsKey to ModelRouteGroupCodec.encode(listOf(group)))
        val corrupt = preferencesOf(groupsKey to "{broken")
        val store = MemoryStore(valid)
        val routes = ModelRouteGroupRepository(store)
        val defaults = spyk(DefaultModelSettingsRepository(RuntimeEnvironment.getApplication(), routes))
        every { defaults.providerIdFlow } returns flowOf(group.selectionId)
        every { defaults.modelFlow } returns flowOf("")
        coEvery { defaults.getDefaultProviderId() } returns group.selectionId
        val providers = mockk<AIProviderRepository>()
        val provider = AIProviderConfig(
            "provider", "Provider", ProviderType.OPENAI, "fixture",
            baseUrl = "", defaultModel = "model", models = listOf("model"),
        )
        every { providers.getAllProviders() } returns flowOf(listOf(provider))
        val title = mockk<TitleModelSettingsRepository>()
        val compaction = mockk<CompactionModelSettingsRepository>()
        val vision = mockk<VisionModelSettingsRepository>()
        val imageGen = mockk<ImageGenModelSettingsRepository>()
        val memory = mockk<MemoryModelSettingsRepository>()
        configureEmptyInputs(title, compaction, vision, imageGen, memory)
        val resolver = ModelSceneResolver(providers, defaults, title, compaction, vision, imageGen, memory)
        val viewModel = ModelSceneViewModel(
            resolver, providers, defaults, title, compaction, vision, imageGen, memory,
        )
        val previews = mutableListOf<Map<ModelScene, SceneResolution>>()
        val lists = mutableListOf<List<ModelRouteGroup>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.resolutions.collect { previews += it }
        }
        val listCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            defaults.routeGroupsFlow.collect { lists += it }
        }
        val bound = SceneResolution.Bound(ModelScene.MAIN_CHAT, "provider", "Provider", "model")
        try {
            advanceUntilIdle()
            assertEquals(bound, previews.last()[ModelScene.MAIN_CHAT])
            assertEquals(listOf(listOf(group)), lists)

            store.current.value = corrupt
            advanceUntilIdle()
            assertEquals(
                SceneResolution.Unavailable(ModelScene.MAIN_CHAT, SceneBindingIssue.RESOLVE_FAILED),
                previews.last()[ModelScene.MAIN_CHAT],
            )
            assertTrue(routes.readState.first().exceptionOrNull() is ModelRouteGroupDataException)
            assertEquals(listOf(listOf(group)), lists)
            assertEquals(corrupt, store.current.value)
            assertEquals(0, store.commits)

            store.current.value = valid
            advanceUntilIdle()
            assertEquals(bound, previews.last()[ModelScene.MAIN_CHAT])
            assertEquals(listOf(listOf(group), listOf(group)), lists)
            assertEquals(0, store.commits)
        } finally {
            collector.cancel()
            listCollector.cancel()
            viewModel.viewModelScope.cancel()
        }
    }

    @Test fun routeInvalidationReadCancellationPropagates() = runTest(dispatcher) {
        val cancelled = CancellationException("route state read cancelled")
        val store = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw cancelled }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                error("Read-only test must not write")
        }
        val defaults = DefaultModelSettingsRepository(
            RuntimeEnvironment.getApplication(), ModelRouteGroupRepository(store),
        )

        try {
            defaults.routeGroupInvalidations.first()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    private fun configureEmptyInputs(
        title: TitleModelSettingsRepository,
        compaction: CompactionModelSettingsRepository,
        vision: VisionModelSettingsRepository,
        imageGen: ImageGenModelSettingsRepository,
        memory: MemoryModelSettingsRepository,
    ) {
        every { title.providerIdFlow } returns flowOf("")
        every { title.modelFlow } returns flowOf("")
        every { compaction.providerIdFlow } returns flowOf("")
        every { compaction.modelFlow } returns flowOf("")
        every { vision.providerIdFlow } returns flowOf("")
        every { vision.modelFlow } returns flowOf("")
        every { imageGen.providerIdFlow } returns flowOf("")
        every { imageGen.modelFlow } returns flowOf("")
        every { memory.providerIdFlow } returns flowOf("")
        every { memory.modelFlow } returns flowOf("")
        coEvery { title.getTitleProviderId() } returns ""
        coEvery { title.getTitleModel() } returns ""
        coEvery { compaction.getCompactionProviderId() } returns ""
        coEvery { compaction.getCompactionModel() } returns ""
        coEvery { vision.getVisionProviderId() } returns ""
        coEvery { vision.getVisionModel() } returns ""
        coEvery { imageGen.getImageGenProviderId() } returns ""
        coEvery { imageGen.getImageGenModel() } returns ""
        coEvery { memory.getMemoryProviderId() } returns ""
        coEvery { memory.getMemoryModel() } returns ""
    }

    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        val current = MutableStateFlow(initial)
        var commits = 0
        override val data = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(current.value)
            current.value = next
            commits++
            return next
        }
    }
}
