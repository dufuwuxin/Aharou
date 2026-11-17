package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.settings.data.repository.ContextPreferences
import com.aharou.feature.settings.data.repository.ContextPreset
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ContextSettingsViewModel @Inject constructor(
    private val repository: GeneralSettingsRepository,
) : ViewModel() {
    val preferences = repository.contextPreferencesFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ContextPreferences(),
    )

    fun applyPreset(preset: ContextPreset) { viewModelScope.launch { repository.applyContextPreset(preset) } }
    fun setRounds(value: Int) { viewModelScope.launch { repository.setContextTrimRounds(value) } }
    fun setSoft(value: Int) { viewModelScope.launch { repository.setSoftCompactionThresholdPercent(value) } }
    fun setHard(value: Int) { viewModelScope.launch { repository.setCompactionThresholdPercent(value) } }
    fun setPreserveSummary(value: Boolean) { viewModelScope.launch { repository.setContextPreserveSummary(value) } }
    fun setKeepTools(value: Int) { viewModelScope.launch { repository.setContextToolResultKeepRecent(value) } }
}
