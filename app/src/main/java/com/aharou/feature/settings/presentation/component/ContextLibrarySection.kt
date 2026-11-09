package com.aharou.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SegmentedTabs
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.settings.presentation.ContextLibraryViewModel
import com.aharou.feature.settings.presentation.ContextLibraryState
import com.aharou.feature.settings.presentation.contextChildFolders
import com.aharou.feature.settings.presentation.contextVisibleEntries
import com.aharou.feature.settings.presentation.contextParentFolder
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder

@Composable
internal fun ContextLibrarySection(viewModel: ContextLibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var category by rememberSaveable { mutableStateOf(ContextCategory.LONG_TERM) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var scope by rememberSaveable { mutableStateOf<MemoryScope?>(null) }
    var loading by rememberSaveable { mutableStateOf<ContextLoad?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var editor by rememberSaveable(stateSaver = ContextEntryStateSaver) { mutableStateOf<ContextEntry?>(null) }
    var editorRoot by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var creatingFolder by rememberSaveable { mutableStateOf(false) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var deletingFolder by remember { mutableStateOf<String?>(null) }
    var deleteCategory by remember { mutableStateOf(category) }
    var deleteScope by remember { mutableStateOf(MemoryScope.GLOBAL) }
    var deleteSnapshot by remember { mutableStateOf<ContextLibraryState?>(null) }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    if (creating || editor != null) {
        val target = editor
        ContextLibraryEditor(
            entry = target, initialCategory = category,
            initialFolder = folder ?: if (category.isInjection) stringResource(category.label()) else "", state = state,
            onBack = { creating = false; editor = null },
            onSave = { name, description, content, entryScope, kind, entryLoad, entryCategory, entryFolder, sessionId ->
                val done = { creating = false; editor = null; category = entryCategory; folder = entryFolder }
                if (target == null) viewModel.create(name, description, content, entryScope, kind, entryLoad,
                    entryCategory, entryFolder, sessionId, editorRoot, done)
                else viewModel.save(target.copy(title = name, description = description, content = content, kind = kind, loading = entryLoad,
                    category = entryCategory, folder = entryFolder, sessionId = sessionId), editorRoot, done)
            },
            onDelete = target?.let { { viewModel.delete(it, editorRoot) { editor = null } } },
        )
        return
    }
    val up = {
        if (tab != 0) tab = 0
        else folder = folder?.takeIf { it.contains('/') }?.let { contextParentFolder(it) }
        query = ""
    }
    BackHandler(enabled = tab != 0 || folder != null) { up() }
    if (creatingFolder) AlertDialog(
        onDismissRequest = { if (!state.writing) creatingFolder = false },
        title = { Text(stringResource(R.string.context_new_folder)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(category.label()), style = MaterialTheme.typography.labelLarge)
            AppTextField(folderName, { folderName = it }, label = stringResource(R.string.context_folder_name))
            Text(stringResource(R.string.context_folder_name_help), style = MaterialTheme.typography.bodySmall)
            if (state.error) ContextNotice(stringResource(R.string.context_save_error), error = true)
        } },
        confirmButton = { TextButton(enabled = !state.writing && folderName.isNotBlank(), onClick = {
            val path = listOf(folder.orEmpty(), folderName.trim()).filter(String::isNotEmpty).joinToString("/")
            viewModel.createFolder(category, path) { folder = path; creatingFolder = false; folderName = ""; query = "" }
        }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(enabled = !state.writing, onClick = { creatingFolder = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
    deletingFolder?.let { path ->
        AlertDialog(
            onDismissRequest = { if (!state.writing) { deletingFolder = null; deleteSnapshot = null } },
            title = { Text(stringResource(R.string.context_delete_folder_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.context_delete_folder_body, path))
                val scopes = if (deleteSnapshot?.hasWorkspace == true) MemoryScope.entries else listOf(MemoryScope.GLOBAL)
                ContextChoiceRow(scopes.map { stringResource(it.label()) }, scopes.indexOf(deleteScope),
                    { if (!state.writing) deleteScope = scopes[it] })
                if (state.error) ContextNotice(stringResource(R.string.context_save_error), error = true)
            } },
            confirmButton = { TextButton(enabled = !state.writing && !state.loading, onClick = {
                deleteSnapshot?.let { snapshot ->
                    viewModel.deleteFolder(deleteCategory, path, deleteScope, snapshot) {
                        folder = contextParentFolder(path)
                        deletingFolder = null
                        deleteSnapshot = null
                        query = ""
                    }
                }
            }) { Text(stringResource(R.string.context_delete_folder)) } },
            dismissButton = { TextButton(enabled = !state.writing, onClick = {
                deletingFolder = null; deleteSnapshot = null
            }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    val currentFolder = folder
    val visible = remember(state.entries, query, scope, loading, category, currentFolder) {
        contextVisibleEntries(state.entries, query, category, currentFolder, scope, loading)
    }
    val folders = remember(state.folders, state.entries, category, currentFolder, query) {
        val paths = state.folders[category].orEmpty() + state.entries.filter { it.category == category }.map { it.folder }
        if (query.isNotBlank()) emptyList() else contextChildFolders(paths, currentFolder.orEmpty())
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg)) {
        item { ContextOverview(state.entries) }
        item { SegmentedTabs(tab, listOf(stringResource(R.string.context_tab_library), stringResource(R.string.context_tab_preview)), { tab = it }) }
        if (state.error) item { ContextNotice(stringResource(R.string.context_operation_error), error = true) }
        if (tab == 1) item { ContextLibraryPreview(state, viewModel::preview, viewModel::clearPreview) }
        else {
            item { ContextChoiceRow(ContextCategory.entries.map { stringResource(it.label()) }, category.ordinal,
                { category = ContextCategory.entries[it]; folder = null; query = "" }) }
            item { Text(stringResource(category.explanation()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (currentFolder != null) item {
                TextButton(onClick = up) { Text(stringResource(R.string.context_folder_up)) }
                Text(stringResource(R.string.context_folder_path, currentFolder.ifEmpty { stringResource(R.string.context_folder_root_title) }),
                    style = MaterialTheme.typography.titleMedium)
                if (viewModel.canDeleteFolder(category, currentFolder)) {
                    TextButton(enabled = !state.loading && !state.writing, onClick = {
                        deleteSnapshot = state
                        deleteCategory = category
                        deleteScope = scope ?: if (state.hasWorkspace) MemoryScope.PROJECT else MemoryScope.GLOBAL
                        deletingFolder = currentFolder
                    }) { Text(stringResource(R.string.context_delete_folder)) }
                }
            }
            item { AppTextField(query, { query = it }, placeholder = stringResource(R.string.context_search_placeholder)) }
            if (currentFolder != null || query.isNotBlank()) {
                item { ContextChoiceRow(listOf(stringResource(R.string.context_scope_all)) + MemoryScope.entries.map { stringResource(it.label()) },
                    (scope?.ordinal ?: -1) + 1, { scope = MemoryScope.entries.getOrNull(it - 1) }) }
                item { ContextChoiceRow(listOf(stringResource(R.string.context_load_all)) + ContextLoad.entries.map { stringResource(it.label()) },
                    (loading?.ordinal ?: -1) + 1, { loading = ContextLoad.entries.getOrNull(it - 1) }) }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = { editorRoot = state.workspaceRoot; creating = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.context_add_entry)) }
                    OutlinedButton(onClick = { creatingFolder = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.context_new_folder)) }
                }
            }
            if (state.loading) item { CircularProgressIndicator() }
            if (currentFolder == null && query.isBlank()) item {
                ContextFolderRow(stringResource(R.string.context_folder_root_title)) { folder = ""; query = "" }
            }
            items(folders, key = { "folder:$it" }) { path ->
                ContextFolderRow(path.substringAfterLast('/')) { folder = path; query = "" }
            }
            if (currentFolder != null || query.isNotBlank()) {
                item { Text(stringResource(R.string.context_result_count, visible.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!state.loading && visible.isEmpty() && folders.isEmpty()) item { ContextNotice(stringResource(R.string.context_folder_empty)) }
                items(visible, key = { it.id }) { entry -> ContextEntryCard(entry, query) { editorRoot = state.workspaceRoot; editor = entry } }
            }
        }
    }
}

@Composable
private fun ContextFolderRow(title: String, onClick: () -> Unit) {
    SettingsGroup { SettingsRow(icon = FeatherIcons.Folder, title = title, onClick = onClick) }
}
