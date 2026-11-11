package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextFolderStore
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLibraryRepository
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.context.ContextOrigin
import com.aharou.feature.agent.domain.context.ContextPreview
import com.aharou.feature.agent.domain.context.ContextSearch
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ContextLibraryState(
    val entries: List<ContextEntry> = emptyList(),
    val loading: Boolean = true,
    val writing: Boolean = false,
    val hasWorkspace: Boolean = false,
    val error: Boolean = false,
    val preview: ContextPreview? = null,
    val previewLoading: Boolean = false,
    val revision: Long = 0,
    val folders: Map<ContextCategory, List<String>> = emptyMap(),
    val sessions: List<Pair<String, String>> = emptyList(),
    val workspaceRoot: String? = null,
    val folderVersion: String = "",
)

@HiltViewModel
class ContextLibraryViewModel @Inject constructor(
    private val library: ContextLibraryRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val folders: ContextFolderStore,
    private val sessions: ChatSessionDao,
) : ViewModel() {
    private val _state = MutableStateFlow(ContextLibraryState())
    val state = _state.asStateFlow()
    private var refreshGeneration = 0L
    private var previewGeneration = 0L

    fun refresh() {
        val generation = ++refreshGeneration
        val root = root()
        _state.value = _state.value.copy(loading = true, hasWorkspace = root != null, workspaceRoot = root, error = false)
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    Triple(library.list(root), folders.snapshot(root), sessions.getRootSessionsByWorkspaceOnce(root.orEmpty())
                        .map { it.id to it.title })
                }
                if (generation == refreshGeneration && root == root()) {
                    val folderSnapshot = snapshot.second
                    _state.value = if (folderSnapshot == null) _state.value.copy(loading = false, error = true)
                    else _state.value.copy(entries = snapshot.first, folders = folderSnapshot.first,
                        folderVersion = folderSnapshot.second, sessions = snapshot.third, loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == refreshGeneration) _state.value = _state.value.copy(loading = false, error = true)
            }
        }
    }

    fun save(entry: ContextEntry, expectedRoot: String?, onSaved: () -> Unit) = write(onSaved) { root ->
        root == expectedRoot && library.save(entry, root)
    }

    fun create(
        name: String,
        description: String,
        content: String,
        scope: MemoryScope,
        kind: ContextKind,
        loading: ContextLoad,
        category: ContextCategory,
        folder: String,
        sessionId: String?,
        expectedRoot: String?,
        onSaved: () -> Unit,
        worldbook: com.aharou.feature.agent.domain.context.WorldbookRules = com.aharou.feature.agent.domain.context.WorldbookRules(),
    ) = write(onSaved) { root ->
        if (root != expectedRoot || (scope == MemoryScope.PROJECT && root == null)) false
        else library.create(name.trim(), description.trim(), content, scope, kind, loading, root,
            category = category, folder = folder.trim(), sessionId = sessionId, worldbook = worldbook)
    }

    fun delete(entry: ContextEntry, expectedRoot: String?, onDeleted: () -> Unit) = write(onDeleted) { root ->
        root == expectedRoot && library.delete(entry, root)
    }

    private fun write(onSuccess: () -> Unit, operation: (String?) -> Boolean) {
        if (_state.value.writing) return
        val root = root()
        _state.value = _state.value.copy(writing = true, error = false)
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { operation(root) }
                if (root != root()) {
                    _state.value = _state.value.copy(error = true)
                } else if (saved) {
                    ++previewGeneration
                    _state.value = _state.value.copy(preview = null, previewLoading = false, revision = _state.value.revision + 1)
                    onSuccess()
                    refresh()
                } else _state.value = _state.value.copy(error = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = _state.value.copy(error = true)
            } finally {
                _state.value = _state.value.copy(writing = false)
            }
        }
    }

    fun createFolder(category: ContextCategory, name: String, onCreated: () -> Unit) = write(onCreated) { root ->
        folders.create(category, name, root)
    }

    fun canDeleteFolder(category: ContextCategory, path: String): Boolean = folders.canDelete(category, path)

    fun deleteFolder(category: ContextCategory, path: String, scope: MemoryScope,
                     expected: ContextLibraryState, onDeleted: () -> Unit) = write(onDeleted) { root ->
        root == expected.workspaceRoot && !expected.loading && expected.folderVersion.isNotBlank() &&
            folders.canDelete(category, path) && library.deleteFolder(category, path, scope, root, expected.entries) {
                folders.delete(category, path, scope, root, expected.folderVersion)
            }
    }

    fun clearPreview() {
        ++previewGeneration
        _state.value = _state.value.copy(preview = null, previewLoading = false)
    }

    fun preview(query: String, sessionId: String?) {
        val generation = ++previewGeneration
        val root = root()
        _state.value = _state.value.copy(previewLoading = true, preview = null, error = false)
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) { library.preview(root, query, sessionId) }
                if (generation == previewGeneration && root == root()) {
                    _state.value = _state.value.copy(preview = preview, previewLoading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == previewGeneration) _state.value = _state.value.copy(previewLoading = false, error = true)
            }
        }
    }

    private fun root(): String? = workspaceRepository.currentPath().takeIf { it.isNotBlank() }
}

internal fun filterContextEntries(
    entries: List<ContextEntry>,
    query: String,
    scope: MemoryScope?,
    loading: ContextLoad?,
    origin: ContextOrigin?,
    category: ContextCategory? = null,
    folder: String? = null,
): List<ContextEntry> {
    val filtered = entries.filter { entry ->
        (category == null || entry.category == category) &&
            (folder == null || entry.folder == folder) &&
            (scope == null || entry.scope == scope) &&
            (loading == null || entry.loading == loading) &&
            (origin == null || entry.origin == origin)
    }
    return ContextSearch.search(filtered, query, includeSource = true)
}
