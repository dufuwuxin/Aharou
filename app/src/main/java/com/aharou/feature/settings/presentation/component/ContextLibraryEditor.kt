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
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.context.ContextLibraryPlanner
import com.aharou.feature.agent.domain.context.WorldbookRules
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.settings.presentation.ContextEditorDraft
import com.aharou.feature.settings.presentation.ContextLibraryState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    var showConditions by rememberSaveable(editorKey) { mutableStateOf(false) }
    var showAdvanced by rememberSaveable(editorKey) { mutableStateOf(false) }
    var showPlacement by rememberSaveable(editorKey) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(editorKey) { mutableStateOf(false) }
    var confirmBack by rememberSaveable(editorKey) { mutableStateOf(false) }
    val worldbook = remember(draft.worldbookJson) { memoryRulesFromDraft(draft.worldbookJson) }
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
            if (draft.loading == ContextLoad.FULL || draft.loading == ContextLoad.INDEX) {
                worldbook?.takeIf { it.enabled && entry?.enabled != false }?.let { rules ->
                    val transient = ContextLibraryPlanner.isTransient(draft.category, draft.content, rules)
                    Text(stringResource(if (transient) R.string.context_injection_transient else R.string.context_injection_stable),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
            CollapsibleGroupHeader(stringResource(R.string.context_injection_conditions), showConditions,
                onToggle = { showConditions = !showConditions })
            if (showConditions) Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.context_injection_budget, ContextLibraryPlanner.FULL_ENTRY_CHARS,
                    ContextLibraryPlanner.FULL_TOTAL_CHARS, ContextLibraryPlanner.INDEX_TOTAL_CHARS),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                key(editorKey) {
                    MemoryEntryFields(draft.worldbookJson, !state.writing) { draft = draft.copy(worldbookJson = it) }
                }
                TextButton(enabled = !state.writing, onClick = { showAdvanced = !showAdvanced }) {
                    Text(stringResource(if (showAdvanced) R.string.context_worldbook_hide_json else R.string.context_worldbook_show_json))
                }
            }
        }
        if ((showConditions && showAdvanced) || worldbook == null) {
            if (worldbook == null) ContextNotice(stringResource(R.string.context_worldbook_recover), error = true)
            AppTextField(value = draft.worldbookJson, onValueChange = { draft = draft.copy(worldbookJson = it) },
                label = stringResource(R.string.context_worldbook_field), singleLine = false, minLines = 4, enabled = !state.writing)
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

/** Raw field text lives in the existing saveable draft, including incomplete numbers and trailing newlines. */
internal fun memoryRulesFromDraft(value: String): WorldbookRules? = runCatching {
    val fields = (Json.parseToJsonElement(value) as JsonObject).toMutableMap()
    for (name in listOf("triggers", "excludes", "roles", "sharedWithRoles", "tags")) {
        val field = fields[name] as? JsonPrimitive ?: continue
        if (!field.isString) continue
        val terms = if (name == "tags") com.aharou.feature.agent.domain.skill.SkillParser.parseKeywordInput(field.content)
            else field.content.lines().map(String::trim).filter(String::isNotEmpty)
        fields[name] = JsonArray(terms.map { JsonPrimitive(it) })
    }
    for (name in listOf("priority", "confidence", "importance")) {
        val field = fields[name] as? JsonPrimitive ?: continue
        if (!field.isString) continue
        fields[name] = when {
            name != "priority" && field.content.isBlank() -> JsonNull
            name == "confidence" -> JsonPrimitive(requireNotNull(field.content.toDoubleOrNull()))
            else -> JsonPrimitive(requireNotNull(field.content.toIntOrNull()))
        }
    }
    (fields["ownerRole"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
        fields["ownerRole"] = it.content.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull
    }
    // Strict decoding intentionally rejects unknown keys: editing another field must never discard them.
    WorldbookRules.fromJson(JsonObject(fields).toString())
}.getOrNull()

@Composable
internal fun MemoryEntryFields(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val fields = remember(value) { runCatching { Json.parseToJsonElement(value) as JsonObject }.getOrNull() } ?: return
    var advanced by rememberSaveable { mutableStateOf(false) }
    fun text(name: String, default: String = ""): String = when (val field = fields[name]) {
        null, JsonNull -> default
        is JsonPrimitive -> field.content
        is JsonArray -> field.joinToString("\n") { (it as? JsonPrimitive)?.content ?: it.toString() }
        else -> field.toString()
    }
    fun update(name: String, field: JsonElement) { onChange(JsonObject(fields + (name to field)).toString()) }
    val confidence = text("confidence")
    val confidenceValue = confidence.toDoubleOrNull()
    val confidenceError = confidence.isNotBlank() &&
        (confidenceValue == null || !confidenceValue.isFinite() || confidenceValue !in 0.0..1.0)
    val importance = text("importance")
    val importanceValue = importance.toIntOrNull()
    val importanceError = importance.isNotBlank() && (importanceValue == null || importanceValue !in 0..100)
    val priorityError = text("priority", "0").toIntOrNull() == null
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        MemoryRuleField(fields, "tags", enabled, onChange, R.string.asset_classification_tags, multiline = true)
        MemoryRuleField(fields, "triggers", enabled, onChange, R.string.context_worldbook_triggers, multiline = true)
        MemoryRuleField(fields, "excludes", enabled, onChange, R.string.context_worldbook_excludes, multiline = true)
        MemoryRuleField(fields, "confidence", enabled, onChange, R.string.context_worldbook_confidence,
            error = R.string.context_worldbook_confidence_error.takeIf { confidenceError })
        MemoryRuleField(fields, "importance", enabled, onChange, R.string.context_worldbook_importance,
            error = R.string.context_worldbook_importance_error.takeIf { importanceError })
        Text(stringResource(R.string.context_worldbook_metadata_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.context_worldbook_match), style = MaterialTheme.typography.labelLarge)
        ContextChoiceRow(listOf(stringResource(R.string.context_worldbook_any), stringResource(R.string.context_worldbook_all)),
            if (text("matchAll") == "true") 1 else 0, { update("matchAll", JsonPrimitive(it == 1)) }, enabled)
        TextButton(enabled = enabled, onClick = { advanced = !advanced }) {
            Text(stringResource(if (advanced) R.string.context_worldbook_less else R.string.context_worldbook_more))
        }
        if (advanced) {
            WorldbookToggle(stringResource(R.string.context_worldbook_enabled), text("enabled", "true") == "true", enabled) {
                update("enabled", JsonPrimitive(it))
            }
            MemoryRuleField(fields, "priority", enabled, onChange, R.string.context_worldbook_priority, default = "0",
                error = R.string.context_worldbook_integer_error.takeIf { priorityError })
            MemoryRuleField(fields, "ownerRole", enabled, onChange, R.string.context_memory_owner)
            MemoryRuleField(fields, "sharedWithRoles", enabled, onChange, R.string.context_memory_shared_roles, multiline = true)
            MemoryRuleField(fields, "roles", enabled, onChange, R.string.context_worldbook_roles, multiline = true)
            WorldbookToggle(stringResource(R.string.context_worldbook_case), text("caseSensitive") == "true", enabled) {
                update("caseSensitive", JsonPrimitive(it))
            }
        }
        if (confidenceError || importanceError || priorityError)
            ContextNotice(stringResource(R.string.context_worldbook_invalid), error = true)
    }
}

@Composable
private fun MemoryRuleField(fields: JsonObject, name: String, enabled: Boolean, onChange: (String) -> Unit,
                            label: Int, multiline: Boolean = false, default: String = "", error: Int? = null) {
    val value = when (val field = fields[name]) {
        null, JsonNull -> default
        is JsonPrimitive -> field.content
        is JsonArray -> field.joinToString("\n") { (it as? JsonPrimitive)?.content ?: it.toString() }
        else -> field.toString()
    }
    key(name) {
        AppTextField(value, { onChange(JsonObject(fields + (name to JsonPrimitive(it))).toString()) },
            label = stringResource(label), singleLine = !multiline, minLines = if (multiline) 2 else 1,
            enabled = enabled, isError = error != null,
            supportingText = { if (error != null) Text(stringResource(error)) })
    }
}

@Composable
private fun WorldbookToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        AppSwitch(checked, onCheckedChange = null, enabled = enabled)
    }
}
