package com.aharou.feature.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.tool.custom.ToolPromptMetadata
import com.aharou.feature.agent.domain.tool.custom.ToolSettingsStore
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PluginPromptDraft(val id: String, val workspace: String, val content: String, val version: String,
                             val saving: Boolean = false, val failed: Boolean = false)

@HiltViewModel
class PluginPromptViewModel @Inject constructor(
    private val store: ToolSettingsStore,
    private val workspace: WorkspaceRepository,
) : ViewModel() {
    private val _draft = MutableStateFlow<PluginPromptDraft?>(null)
    val draft = _draft.asStateFlow()
    private var request = 0L

    fun open(id: String) {
        val currentRequest = ++request
        val root = workspace.currentPath()
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { store.snapshotForProject(root) }
            if (currentRequest != request || workspace.currentPath() != root) return@launch
            val key = ToolPromptMetadata.pluginKey(id)
            _draft.value = PluginPromptDraft(id, root, snapshot.overrides[key]?.description.orEmpty(),
                ToolPromptMetadata.version(snapshot, key, ""))
        }
    }

    fun close() { if (_draft.value?.saving != true) { request++; _draft.value = null } }

    fun save(text: String?) {
        val draft = _draft.value ?: return
        if (draft.saving) return
        if (workspace.currentPath() != draft.workspace || (text?.length ?: 0) > 40000) {
            _draft.value = draft.copy(failed = true)
            return
        }
        _draft.value = draft.copy(saving = true)
        viewModelScope.launch {
            val error = try {
                withContext(Dispatchers.IO) {
                    store.editPrompt(ToolPromptMetadata.pluginKey(draft.id), text, draft.version, draft.workspace) { "" }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                "PROMPT_WRITE_FAILED"
            }
            if (error == null) _draft.value = null else _draft.value = draft.copy(failed = true)
        }
    }
}
