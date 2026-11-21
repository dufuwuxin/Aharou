package com.aharou.feature.settings.presentation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.ModelRouteGroupRepository
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteGroupCodec
import com.aharou.feature.settings.domain.model.ModelRouteMember
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelRouteGroupsSectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<ModelRouteGroupViewModel>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun coldStartCorruptionIsVisibleAndRecoveryClearsErrorWhileRetainingLastValidList() = runTest(dispatcher) {
        val groupsKey = stringPreferencesKey("groups_json")
        val preferences = MutableStateFlow(preferencesOf(groupsKey to "{broken"))
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = preferences
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("unexpected write")
        }
        val defaults = mockk<DefaultModelSettingsRepository>()
        every { defaults.providerIdFlow } returns flowOf("")
        val viewModel = ModelRouteGroupViewModel(ModelRouteGroupRepository(store), defaults).also(viewModels::add)

        runCurrent()
        assertTrue(viewModel.readError.value)
        assertTrue(viewModel.groups.value.isEmpty())

        val group = ModelRouteGroup("repaired", "Repaired", listOf(ModelRouteMember("provider", "model")))
        preferences.value = preferencesOf(groupsKey to ModelRouteGroupCodec.encode(listOf(group)))
        runCurrent()
        assertFalse(viewModel.readError.value)
        assertEquals(listOf(group), viewModel.groups.value)

        preferences.value = preferencesOf(groupsKey to "{broken again")
        runCurrent()
        assertTrue(viewModel.readError.value)
        assertEquals(listOf(group), viewModel.groups.value)

        preferences.value = preferencesOf(groupsKey to "[]")
        runCurrent()
        assertFalse(viewModel.readError.value)
        assertTrue(viewModel.groups.value.isEmpty())
    }
}
