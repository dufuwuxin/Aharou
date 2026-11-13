package com.aharou.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.context.WorldbookRules
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.settings.presentation.ContextEditorDraft
import com.aharou.feature.settings.presentation.ContextLibraryState

@Composable
internal fun ContextLibraryEditor(
    entry: ContextEntry?,
    initialCategory: ContextCategory,
    initialFolder: String,
    state: ContextLibraryState,
    onBack: () -> Unit,
    onSave: (String, String, String, MemoryScope, ContextKind, ContextLoad, ContextCategory, String, String?, WorldbookRules) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val editorKey = entry?.id ?: "new-${initialCategory.name}"
    val original = remember(entry, initialCategory, initialFolder, state.hasWorkspace) {
        ContextEditorDraft.from(entry, initialCategory, initialFolder, state.hasWorkspace)
    }
    var draft by rememberSaveable(editorKey, stateSaver = ContextEditorDraftSaver) { mutableStateOf(original) }
    var showRules by rememberSaveable(editorKey) { mutableStateOf(false) }
    var showAdvanced by rememberSaveable(editorKey) { mutableStateOf(false) }
    var showPlacement by rememberSaveable(editorKey) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(editorKey) { mutableStateOf(false) }
    var confirmBack by rememberSaveable(editorKey) { mutableStateOf(false) }
    val worldbook = remember(draft.worldbookJson) { runCatching { WorldbookRules.fromJson(draft.worldbookJson) }.getOrNull() }
    if (showRules && worldbook != null) WorldbookStructuredEditor(
        worldbook, enabled = !state.writing,
        onDismiss = { showRules = false },
        onApply = { draft = draft.copy(worldbookJson = it.toJson()); showRules = false },
    )
    val dirty = draft != original
    val back = { if (!state.writing) { if (dirty) confirmBack = true else onBack() } }
    BackHandler(enabled = true) { back() }
    if (confirmBack) AlertDialog(
        onDismissRequest = { confirmBack = false }, title = { Text(stringResource(R.string.context_discard_title)) },
        text = { Text(stringResource(R.string.context_discard_body)) },
        confirmButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.context_discard)) } },
        dismissButton = { TextButton(onClick = { confirmBack = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
    if (confirmDelete && onDelete != null) AlertDialog(
        onDismissRequest = { if (!state.writing) confirmDelete = false },
        title = { Text(stringResource(R.string.context_delete_title)) },
        text = { Text(stringResource(R.string.context_delete_body)) },
        confirmButton = { TextButton(enabled = !state.writing, onClick = onDelete) { Text(stringResource(R.string.memory_delete)) } },
        dismissButton = { TextButton(enabled = !state.writing, onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
    Column(modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = !state.writing, onClick = back) { Text(stringResource(R.string.context_back_library)) }
            if (onDelete != null) TextButton(enabled = !state.writing, onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.memory_delete), color = MaterialTheme.colorScheme.error)
            }
        }
        Text(stringResource(if (entry == null) R.string.context_new_title else R.string.context_edit_title), style = MaterialTheme.typography.headlineSmall)
        if (state.error) ContextNotice(stringResource(R.string.context_save_error), error = true)
        if (entry?.pending == true) ContextNotice(stringResource(R.string.context_pending_help))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.context_field_loading), style = MaterialTheme.typography.titleSmall)
            ContextChoiceRow(ContextLoad.entries.map { stringResource(it.label()) }, draft.loading.ordinal,
                { draft = draft.copy(loading = ContextLoad.entries[it]) }, !state.writing)
            Text(stringResource(draft.loading.explanation()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        key("name") {
            AppTextField(value = draft.name, onValueChange = { draft = draft.copy(name = it) },
                label = stringResource(R.string.context_field_title), enabled = !state.writing)
        }
        key("description") {
            AppTextField(value = draft.description, onValueChange = { draft = draft.copy(description = it) },
                label = stringResource(R.string.context_field_description), enabled = !state.writing)
        }
        key("content") {
            AppTextField(value = draft.content, onValueChange = { draft = draft.copy(content = it) }, label = stringResource(R.string.context_field_content),
                singleLine = false, minLines = 5, enabled = !state.writing)
        }
        com.aharou.core.translation.TranslationPreview(draft.content)
        SettingsGroup {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.context_worldbook_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.context_worldbook_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled = !state.writing && worldbook != null, onClick = { showRules = true }) {
                    Text(stringResource(R.string.context_worldbook_edit_rules))
                }
                TextButton(enabled = !state.writing, onClick = { showAdvanced = !showAdvanced }) {
                    Text(stringResource(if (showAdvanced) R.string.context_worldbook_hide_json else R.string.context_worldbook_show_json))
                }
                if (showAdvanced || worldbook == null) {
                    if (worldbook == null) ContextNotice(stringResource(R.string.context_worldbook_recover), error = true)
                    AppTextField(value = draft.worldbookJson, onValueChange = { draft = draft.copy(worldbookJson = it) },
                        label = stringResource(R.string.context_worldbook_field), singleLine = false, minLines = 4, enabled = !state.writing)
                    TextButton(enabled = !state.writing && worldbook != null, onClick = {
                        worldbook?.let { draft = draft.copy(worldbookJson = it.toJson()) }
                    }) { Text(stringResource(R.string.context_worldbook_validate)) }
                }
            }
        }
        TextButton(enabled = !state.writing, onClick = { showPlacement = !showPlacement }) {
            Text(stringResource(if (showPlacement) R.string.context_hide_placement else R.string.context_show_placement))
        }
        if (showPlacement) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.context_field_category), style = MaterialTheme.typography.titleSmall)
                ContextChoiceRow(ContextCategory.entries.map { stringResource(it.label()) }, draft.category.ordinal,
                    { draft = draft.changeCategory(ContextCategory.entries[it]) }, !state.writing)
                Text(stringResource(draft.category.explanation()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppTextField(value = draft.folder, onValueChange = { draft = draft.copy(folder = it) }, label = stringResource(R.string.context_field_folder),
                placeholder = stringResource(R.string.context_folder_root), enabled = !state.writing)
            val existingFolders = state.folders[draft.category].orEmpty()
            if (existingFolders.isNotEmpty()) ContextChoiceRow(existingFolders, existingFolders.indexOf(draft.folder),
                { draft = draft.copy(folder = existingFolders[it]) }, !state.writing)
            if (entry == null) {
                ContextChoiceRow(MemoryScope.entries.map { stringResource(it.label()) }, draft.scope.ordinal, { index ->
                    if (MemoryScope.entries[index] != MemoryScope.PROJECT || state.hasWorkspace) draft = draft.copy(scope = MemoryScope.entries[index])
                }, !state.writing)
            }
            if (draft.category == ContextCategory.TEMPORARY) {
                Text(stringResource(R.string.context_target_session), style = MaterialTheme.typography.titleSmall)
                if (state.sessions.isEmpty()) ContextNotice(stringResource(R.string.context_no_sessions))
                else ContextChoiceRow(state.sessions.map { it.second }, state.sessions.indexOfFirst { it.first == draft.sessionId },
                    { draft = draft.copy(sessionId = state.sessions[it].first) }, !state.writing)
                ContextNotice(stringResource(R.string.context_temporary_help))
            }
            if (draft.category != ContextCategory.RULE) ContextChoiceRow(ContextKind.entries.map { stringResource(it.label()) }, draft.kind.ordinal,
                { draft = draft.copy(kind = ContextKind.entries[it]) }, !state.writing)
        }
        if (worldbook == null) ContextNotice(stringResource(R.string.context_worldbook_invalid), error = true)
        Button(modifier = Modifier.fillMaxWidth(), enabled = !state.writing && worldbook != null && draft.name.isNotBlank() && draft.content.isNotBlank() &&
            (draft.category != ContextCategory.TEMPORARY || draft.sessionId != null), onClick = {
                worldbook?.let { rules ->
                    onSave(draft.name, draft.description, draft.content, draft.scope, draft.kind, draft.loading,
                        draft.category, draft.folder, draft.sessionId, rules)
                }
            }) { Text(stringResource(if (state.writing) R.string.context_saving else R.string.common_save)) }
    }
}

@Composable
internal fun ContextLibraryPreview(state: ContextLibraryState, onRun: (String, String?) -> Unit, onInvalidate: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Text(stringResource(R.string.context_preview_title), style = MaterialTheme.typography.titleLarge)
        ContextNotice(stringResource(R.string.context_preview_help))
        AppTextField(value = query, onValueChange = { query = it; onInvalidate() }, label = stringResource(R.string.context_preview_query), singleLine = false, minLines = 2)
        if (state.sessions.isNotEmpty()) ContextChoiceRow(listOf(stringResource(R.string.context_no_session)) + state.sessions.map { it.second },
            state.sessions.indexOfFirst { it.first == sessionId } + 1,
            { index -> sessionId = state.sessions.getOrNull(index - 1)?.first; onInvalidate() })
        Button(enabled = !state.previewLoading, onClick = { onRun(query, sessionId) }) { Text(stringResource(R.string.context_preview_run)) }
        if (state.previewLoading) CircularProgressIndicator()
        state.preview?.let { preview ->
            Text(stringResource(R.string.context_preview_summary, preview.full.size, preview.indexed.size, preview.omitted.size), style = MaterialTheme.typography.titleMedium)
            preview.full.forEach { ContextNotice(stringResource(R.string.context_preview_full_entry, it.title)) }
            preview.indexed.forEach { Text(stringResource(R.string.context_preview_index_entry, it.title), style = MaterialTheme.typography.bodyMedium) }
            (preview.full + preview.indexed).filter { it.worldbook != WorldbookRules() }.forEach { entry ->
                Text(stringResource(R.string.context_worldbook_explanation, entry.title, preview.explanations[entry.id].orEmpty()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (preview.rendered.isNotBlank()) SelectionContainer { Text(preview.rendered, style = MaterialTheme.typography.bodySmall) }
            if (preview.omitted.isNotEmpty()) {
                Text(stringResource(R.string.context_preview_omitted), style = MaterialTheme.typography.titleSmall)
                preview.omitted.forEach { (entry, reason) ->
                    Text(stringResource(R.string.context_preview_reason, entry.title, reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldbookStructuredEditor(
    rules: WorldbookRules,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onApply: (WorldbookRules) -> Unit,
) {
    var triggers by rememberSaveable { mutableStateOf(rules.triggers.joinToString("\n")) }
    var excludes by rememberSaveable { mutableStateOf(rules.excludes.joinToString("\n")) }
    var roles by rememberSaveable { mutableStateOf(rules.roles.joinToString("\n")) }
    var owner by rememberSaveable { mutableStateOf(rules.ownerRole.orEmpty()) }
    var sharedWith by rememberSaveable { mutableStateOf(rules.sharedWithRoles.joinToString("\n")) }
    var active by rememberSaveable { mutableStateOf(rules.enabled) }
    var matchAll by rememberSaveable { mutableStateOf(rules.matchAll) }
    var caseSensitive by rememberSaveable { mutableStateOf(rules.caseSensitive) }
    var priority by rememberSaveable { mutableStateOf(rules.priority.toString()) }
    var confidence by rememberSaveable { mutableStateOf(rules.confidence?.toString().orEmpty()) }
    var importance by rememberSaveable { mutableStateOf(rules.importance?.toString().orEmpty()) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val priorityValue = priority.toIntOrNull()
    val confidenceValue = confidence.toDoubleOrNull()
    val importanceValue = importance.toIntOrNull()
    val priorityError = priorityValue == null
    val confidenceError = confidence.isNotBlank() &&
        (confidenceValue == null || !confidenceValue.isFinite() || confidenceValue !in 0.0..1.0)
    val importanceError = importance.isNotBlank() && (importanceValue == null || importanceValue !in 0..100)
    val valid = !priorityError && !confidenceError && !importanceError
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Text(stringResource(R.string.context_worldbook_edit_rules), style = MaterialTheme.typography.titleLarge)
            ContextNotice(stringResource(R.string.context_worldbook_form_help))
            SettingsGroup {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    WorldbookToggle(stringResource(R.string.context_worldbook_enabled), active, enabled) { active = it }
                    AppTextField(triggers, { triggers = it }, label = stringResource(R.string.context_worldbook_triggers),
                        singleLine = false, minLines = 2, enabled = enabled)
                    AppTextField(excludes, { excludes = it }, label = stringResource(R.string.context_worldbook_excludes),
                        singleLine = false, minLines = 2, enabled = enabled)
                    Text(stringResource(R.string.context_worldbook_match), style = MaterialTheme.typography.labelLarge)
                    ContextChoiceRow(listOf(stringResource(R.string.context_worldbook_any), stringResource(R.string.context_worldbook_all)),
                        if (matchAll) 1 else 0, { matchAll = it == 1 }, enabled)
                    AppTextField(priority, { priority = it }, label = stringResource(R.string.context_worldbook_priority),
                        isError = priorityError, supportingText = {
                            Text(stringResource(if (priorityError) R.string.context_worldbook_integer_error else R.string.context_worldbook_priority_help))
                        }, enabled = enabled)
                }
            }
            TextButton(onClick = { advanced = !advanced }) {
                Text(stringResource(if (advanced) R.string.context_worldbook_less else R.string.context_worldbook_more))
            }
            if (advanced) SettingsGroup {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    AppTextField(owner, { owner = it }, label = stringResource(R.string.context_memory_owner),
                        supportingText = { Text(stringResource(R.string.context_memory_owner_help)) }, enabled = enabled)
                    AppTextField(sharedWith, { sharedWith = it }, label = stringResource(R.string.context_memory_shared_roles),
                        singleLine = false, minLines = 2, enabled = enabled)
                    AppTextField(roles, { roles = it }, label = stringResource(R.string.context_worldbook_roles),
                        supportingText = { Text(stringResource(R.string.context_worldbook_roles_help)) },
                        singleLine = false, minLines = 2, enabled = enabled)
                    WorldbookToggle(stringResource(R.string.context_worldbook_case), caseSensitive, enabled) { caseSensitive = it }
                    AppTextField(confidence, { confidence = it }, label = stringResource(R.string.context_worldbook_confidence),
                        isError = confidenceError, supportingText = {
                            Text(stringResource(if (confidenceError) R.string.context_worldbook_confidence_error else R.string.context_worldbook_metadata_help))
                        }, enabled = enabled)
                    AppTextField(importance, { importance = it }, label = stringResource(R.string.context_worldbook_importance),
                        isError = importanceError, supportingText = {
                            Text(stringResource(if (importanceError) R.string.context_worldbook_importance_error else R.string.context_worldbook_metadata_help))
                        }, enabled = enabled)
                }
            }
            if (!valid) ContextNotice(stringResource(R.string.context_worldbook_numeric_error), error = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(enabled = enabled && valid, onClick = {
                    onApply(rules.copy(enabled = active, triggers = worldbookLines(triggers), excludes = worldbookLines(excludes),
                        matchAll = matchAll, caseSensitive = caseSensitive, priority = requireNotNull(priorityValue),
                        roles = worldbookLines(roles), confidence = confidenceValue, importance = importanceValue,
                        ownerRole = owner.trim().takeIf { it.isNotEmpty() }, sharedWithRoles = worldbookLines(sharedWith)))
                }) { Text(stringResource(R.string.context_worldbook_apply)) }
            }
        }
    }
}

private fun worldbookLines(value: String): List<String> = value.lines().map(String::trim).filter(String::isNotEmpty)

@Composable
private fun WorldbookToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        AppSwitch(checked, onCheckedChange = null, enabled = enabled)
    }
}
