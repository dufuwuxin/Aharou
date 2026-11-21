package com.aharou.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
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
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
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
            onSave = { name, description, content, entryScope, kind, entryLoad, entryCategory, entryFolder, sessionId, worldbook ->
                val done = {
                    creating = false
                    editor = null
                    category = entryCategory
                    folder = entryFolder.takeIf(String::isNotEmpty)
                    query = ""
                }
                if (target == null) viewModel.create(name, description, content, entryScope, kind, entryLoad,
                    entryCategory, entryFolder, sessionId, editorRoot, done, worldbook = worldbook)
                else viewModel.save(target.copy(title = name, description = description, content = content, kind = kind, loading = entryLoad,
                    category = entryCategory, folder = entryFolder, sessionId = sessionId, worldbook = worldbook), editorRoot, done)
            },
            onDelete = target?.let { { viewModel.delete(it, editorRoot) { editor = null } } },
        )
        return
    }
    val up = {
        if (tab != 0) tab = 0
        else if (query.isNotBlank()) query = ""
        else folder = folder?.let(::contextParentFolder)?.takeIf(String::isNotEmpty)
    }
    BackHandler(enabled = tab != 0 || !folder.isNullOrEmpty() || query.isNotBlank()) { up() }
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
    val currentFolder = folder.orEmpty()
    val searchRequest = remember(state.entries, query, scope, loading, category, currentFolder) { Any() }
    val searchResult by produceState<Pair<Any, List<ContextEntry>>?>(initialValue = null, searchRequest) {
        val entries = withContext(Dispatchers.Default) {
            contextVisibleEntries(state.entries, query, category, currentFolder, scope, loading)
        }
        value = searchRequest to entries
    }
    val visible = searchResult?.takeIf { it.first === searchRequest }?.second.orEmpty()
    val folders = remember(state.folders, state.entries, category, currentFolder, query) {
        val paths = state.folders[category].orEmpty() + state.entries.filter { it.category == category }.map { it.folder }
        if (query.isNotBlank()) emptyList() else contextChildFolders(paths, currentFolder)
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.context_library_heading), style = MaterialTheme.typography.headlineSmall)
            SegmentedTabs(tab, listOf(stringResource(R.string.context_tab_library), stringResource(R.string.context_tab_preview),
                stringResource(R.string.context_graph_title), stringResource(R.string.context_resource_tree)), { tab = it })
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("context_library_content"),
            verticalArrangement = Arrangement.spacedBy(Spacing.md), contentPadding = PaddingValues(Spacing.lg)) {
            if (state.error) item { ContextNotice(stringResource(R.string.context_operation_error), error = true) }
            when (tab) {
                1 -> item { ContextLibraryPreview(state, viewModel::preview, viewModel::clearPreview) }
                2 -> item {
                    ContextMemoryGraph(state.entries,
                        onSelect = { editorRoot = state.workspaceRoot; editor = it },
                        onEditRules = {
                            tab = 0
                            category = ContextCategory.RULE
                            folder = null
                            query = ""
                            scope = null
                            loading = null
                        },
                    )
                }
                3 -> item { ContextResourceTree(state.entries) { editorRoot = state.workspaceRoot; editor = it } }
                else -> {
                    item {
                        AppTextField(query, { query = it }, label = stringResource(R.string.context_search_placeholder),
                            modifier = Modifier.fillMaxWidth().testTag("context_library_search"))
                    }
                    if (query.isBlank()) item {
                        ContextChoiceRow(ContextCategory.entries.map { stringResource(it.label()) }, category.ordinal,
                            { category = ContextCategory.entries[it]; folder = null })
                    }
                    item {
                        val filterLabel = if (scope == null && loading == null) stringResource(R.string.context_library_filters)
                        else listOfNotNull(scope?.let { stringResource(it.label()) }, loading?.let { stringResource(it.label()) })
                            .joinToString(" · ")
                        SettingsGroup {
                            CollapsibleGroupHeader(filterLabel, filtersExpanded, { filtersExpanded = !filtersExpanded },
                                Modifier.heightIn(min = 48.dp))
                            if (filtersExpanded) Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                                ContextChoiceRow(listOf(stringResource(R.string.context_scope_all)) + MemoryScope.entries.map { stringResource(it.label()) },
                                    (scope?.ordinal ?: -1) + 1, { scope = MemoryScope.entries.getOrNull(it - 1) })
                                ContextChoiceRow(listOf(stringResource(R.string.context_load_all)) + ContextLoad.entries.map { stringResource(it.label()) },
                                    (loading?.ordinal ?: -1) + 1, { loading = ContextLoad.entries.getOrNull(it - 1) })
                            }
                        }
                    }
                    if (currentFolder.isNotEmpty() && query.isBlank()) item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = up) { Text(stringResource(R.string.context_folder_up)) }
                            if (viewModel.canDeleteFolder(category, currentFolder)) {
                                TextButton(enabled = !state.loading && !state.writing, onClick = {
                                    deleteSnapshot = state
                                    deleteCategory = category
                                    deleteScope = scope ?: if (state.hasWorkspace) MemoryScope.PROJECT else MemoryScope.GLOBAL
                                    deletingFolder = currentFolder
                                }) { Text(stringResource(R.string.context_delete_folder)) }
                            }
                        }
                        Text(stringResource(R.string.context_folder_path, currentFolder), style = MaterialTheme.typography.titleMedium)
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Button(onClick = { editorRoot = state.workspaceRoot; creating = true }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.context_add_entry))
                            }
                            OutlinedButton(onClick = { creatingFolder = true }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.context_new_folder))
                            }
                        }
                    }
                    if (state.loading) item { CircularProgressIndicator() }
                    if (query.isNotBlank()) item {
                        Text(stringResource(R.string.context_result_count, visible.size), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val pending = state.entries.count { it.pending }
                    if (pending > 0 && query.isBlank()) item { ContextNotice(stringResource(R.string.context_pending_count, pending)) }
                    items(folders, key = { "folder:$it" }) { path ->
                        ContextFolderRow(path.substringAfterLast('/')) { folder = path }
                    }
                    if (!state.loading && searchResult?.first === searchRequest && visible.isEmpty() && folders.isEmpty()) {
                        item { ContextNotice(stringResource(R.string.context_folder_empty)) }
                    }
                    items(visible, key = { it.id }) { entry ->
                        ContextEntryCard(entry, query) { editorRoot = state.workspaceRoot; editor = entry }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextFolderRow(title: String, onClick: () -> Unit) {
    SettingsGroup { SettingsRow(icon = FeatherIcons.Folder, title = title, onClick = onClick) }
}
