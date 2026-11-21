package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.settings.data.repository.ModelRouteGroupRepository
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteMember
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import javax.inject.Inject

@HiltViewModel
class ModelRouteGroupViewModel @Inject constructor(
    private val repository: ModelRouteGroupRepository,
    private val defaultModel: DefaultModelSettingsRepository,
) : ViewModel() {
    private val _groups = MutableStateFlow<List<ModelRouteGroup>>(emptyList())
    val groups: StateFlow<List<ModelRouteGroup>> = _groups.asStateFlow()

    private val _readError = MutableStateFlow(false)
    val readError: StateFlow<Boolean> = _readError.asStateFlow()

    init {
        viewModelScope.launch {
            repository.readState.collect { result ->
                result.onSuccess { _groups.value = it }
                _readError.value = result.isFailure
            }
        }
    }

    val selectedGroupId = defaultModel.providerIdFlow
        .map { ModelRouteGroup.idFromSelection(it).orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    suspend fun create(name: String, models: List<ModelRouteMember>): ModelRouteGroup =
        repository.create(name, models)

    suspend fun update(id: String, name: String, models: List<ModelRouteMember>): ModelRouteGroup =
        repository.update(id, name, models)

    suspend fun delete(id: String): Boolean {
        val removed = repository.delete(id)
        if (removed) defaultModel.clearRoute(id)
        return removed
    }

    suspend fun select(id: String): ModelRouteGroup {
        val group = repository.get(id) ?: error("model route group not found: $id")
        defaultModel.setDefaultModel(group.selectionId, group.id)
        return group
    }
}
