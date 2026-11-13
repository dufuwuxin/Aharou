package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowEntry
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowForm
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowRepository
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowSaveError
import com.aharou.feature.agent.domain.teamworkflow.toForm
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TeamWorkflowEditor(
    val form: TeamWorkflowForm,
    val originalName: String? = null
)

data class TeamWorkflowSettingsState(
    val workflows: List<TeamWorkflowEntry> = emptyList(),
    val availableTeams: List<String> = emptyList(),
    val editor: TeamWorkflowEditor? = null,
    val pendingDelete: TeamWorkflowEntry? = null,
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val deleteFailed: Boolean = false,
    val saveError: TeamWorkflowSaveError? = null
)

@HiltViewModel
class TeamWorkflowSettingsViewModel @Inject constructor(
    private val repository: TeamWorkflowRepository,
    private val teams: AgentTeamRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(TeamWorkflowSettingsState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    init { refresh() }

    fun refresh() {
        if (state.value.saving || state.value.deleting) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { load() }
    }

    fun startCreate() {
        if (state.value.saving || state.value.deleting) return
        mutableState.value = state.value.copy(editor = TeamWorkflowEditor(TeamWorkflowForm()), saveError = null)
        refresh()
    }

    fun startEdit(entry: TeamWorkflowEntry) {
        if (state.value.saving || state.value.deleting) return
        mutableState.value = state.value.copy(
            editor = TeamWorkflowEditor(entry.workflow.toForm(entry.scope), entry.workflow.name), saveError = null
        )
        refresh()
    }

    fun updateForm(form: TeamWorkflowForm) {
        val editor = state.value.editor ?: return
        if (state.value.saving) return
        // Editing may rename a file, but must never silently move it to another scope.
        val scoped = if (editor.originalName == null) form else form.copy(scope = editor.form.scope)
        mutableState.value = state.value.copy(editor = editor.copy(form = scoped), saveError = null)
    }

    fun dismissEditor() {
        if (!state.value.saving) mutableState.value = state.value.copy(editor = null, saveError = null)
    }

    fun save() {
        val editor = state.value.editor ?: return
        if (state.value.saving || state.value.deleting) return
        refreshJob?.cancel()
        mutableState.value = state.value.copy(saving = true, loading = false, saveError = null)
        viewModelScope.launch {
            try {
                val error = withContext(Dispatchers.IO) { repository.save(editor.form, editor.originalName) }
                mutableState.value = if (error == null) state.value.copy(editor = null)
                    else state.value.copy(saveError = error)
                if (error == null) load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = state.value.copy(saveError = TeamWorkflowSaveError.IO_FAILED)
            } finally {
                mutableState.value = state.value.copy(saving = false)
            }
        }
    }

    fun requestDelete(entry: TeamWorkflowEntry) {
        if (state.value.saving || state.value.deleting) return
        mutableState.value = state.value.copy(pendingDelete = entry, deleteFailed = false)
    }

    fun dismissDelete() {
        if (!state.value.deleting) mutableState.value = state.value.copy(pendingDelete = null, deleteFailed = false)
    }

    fun confirmDelete() {
        val entry = state.value.pendingDelete ?: return
        if (state.value.saving || state.value.deleting) return
        refreshJob?.cancel()
        mutableState.value = state.value.copy(deleting = true, loading = false, deleteFailed = false)
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) { repository.delete(entry.workflow.name, entry.scope) }
                mutableState.value = if (deleted) state.value.copy(pendingDelete = null)
                    else state.value.copy(deleteFailed = true)
                if (deleted) load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = state.value.copy(deleteFailed = true)
            } finally {
                mutableState.value = state.value.copy(deleting = false)
            }
        }
    }

    private suspend fun load() {
        mutableState.value = state.value.copy(loading = true, loadFailed = false)
        try {
            val (workflows, names) = withContext(Dispatchers.IO) {
                repository.listAll() to teams.listAll().map { it.team.name }
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            mutableState.value = state.value.copy(workflows = workflows, availableTeams = names)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.value = state.value.copy(loadFailed = true)
        } finally {
            mutableState.value = state.value.copy(loading = false)
        }
    }
}
