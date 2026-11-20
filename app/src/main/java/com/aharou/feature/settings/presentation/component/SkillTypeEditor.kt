package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.dialogTextFieldColors
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.agent.domain.skill.SkillType
import com.aharou.feature.settings.presentation.SkillSettingsController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SkillTypeEditorEntryPoint {
    fun skillSettingsController(): SkillSettingsController
}

@Composable
private fun rememberSkillTypeController(): SkillSettingsController {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors.fromApplication(context, SkillTypeEditorEntryPoint::class.java).skillSettingsController()
    }
}

/** The same scoped collection supports both the skill picker and standalone type management. */
@Composable
internal fun SkillTypeSelector(scope: SkillScope, typeIds: List<String>, onTypeChange: ((List<String>) -> Unit)?) {
    val controller = rememberSkillTypeController()
    val workspaceFlow = controller.skillTypeWorkspaceChanges
    val workspaceState = workspaceFlow?.collectAsStateWithLifecycle()
    val workspaceKey = workspaceState?.value?.path ?: controller.skillTypeWorkspaceKey()
    var types by remember(scope, workspaceKey) { mutableStateOf<List<SkillType>>(emptyList()) }
    var refresh by remember(scope) { mutableStateOf(0) }
    var loading by remember(scope) { mutableStateOf(true) }
    var failed by remember(scope) { mutableStateOf(false) }
    var editing by remember(scope, workspaceKey) { mutableStateOf<SkillType?>(null) }
    var creating by remember(scope, workspaceKey) { mutableStateOf(false) }
    LaunchedEffect(scope, refresh, workspaceKey) {
        loading = true
        failed = false
        try { types = controller.loadSkillTypes(scope) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            failed = true
        } finally { loading = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.skills_type_scope_hint), style = MaterialTheme.typography.bodySmall)
        if (failed) Text(stringResource(R.string.skills_type_save_failed), color = MaterialTheme.colorScheme.error)
        if (onTypeChange != null) {
            Text(stringResource(R.string.skills_types_multi_hint), style = MaterialTheme.typography.bodySmall)
            FilterChip(selected = typeIds.isEmpty(), onClick = { onTypeChange(emptyList()) },
                label = { Text(stringResource(R.string.skills_type_none)) })
            if (!loading && typeIds.any { id -> types.none { it.id == id } }) {
                Text(stringResource(R.string.skills_editor_error_unknown_type), color = MaterialTheme.colorScheme.error)
            }
        }
        types.forEach { type ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (onTypeChange != null) FilterChip(selected = type.id in typeIds, onClick = { onTypeChange(com.aharou.feature.settings.presentation.toggleSkillType(typeIds, type.id)) },
                    modifier = Modifier.weight(1f), label = { Text(type.name) })
                else Text(type.name, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = type }) { Text(stringResource(R.string.common_edit)) }
            }
        }
        TextButton(onClick = { creating = true }, enabled = !loading) { Text(stringResource(R.string.skills_type_create)) }
    }
    if (creating || editing != null) {
        SkillTypeEditor(initial = editing, scope = scope, controller = controller,
            onDismiss = { creating = false; editing = null },
            onSaved = { creating = false; editing = null; refresh++ })
    }
}

@Composable
internal fun SkillTypeManager() {
    var open by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf(SkillScope.GLOBAL) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.skills_type_manage)) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.skills_type_manage)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(scope == SkillScope.GLOBAL, { scope = SkillScope.GLOBAL }, label = { Text(stringResource(R.string.skills_scope_global)) })
                    FilterChip(scope == SkillScope.PROJECT, { scope = SkillScope.PROJECT }, label = { Text(stringResource(R.string.skills_scope_project)) })
                }
                SkillTypeSelector(scope, emptyList(), null)
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_back)) } }
    )
}

@Composable
internal fun SkillTypeEditor(
    initial: SkillType?, scope: SkillScope, controller: SkillSettingsController,
    onDismiss: () -> Unit, onSaved: () -> Unit
) {
    val expectedWorkspace = remember { controller.skillTypeWorkspaceKey() }
    val id = remember(initial) { initial?.id ?: UUID.randomUUID().toString() }
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var semantics by remember(initial) { mutableStateOf(initial?.semantics.orEmpty()) }
    var functions by remember(initial) { mutableStateOf(initial?.functions.orEmpty()) }
    var tags by remember(initial) { mutableStateOf(initial?.tags.orEmpty().joinToString("\n")) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (initial == null) R.string.skills_type_create else R.string.skills_type_edit)) },
        text = {
            Column(modifier = Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AppTextField(name, { name = it }, label = stringResource(R.string.common_name), singleLine = true, colors = dialogTextFieldColors())
                AppTextField(semantics, { semantics = it }, label = stringResource(R.string.skills_type_semantics), singleLine = false, minLines = 3, colors = dialogTextFieldColors())
                AppTextField(functions, { functions = it }, label = stringResource(R.string.skills_type_functions), singleLine = false, minLines = 3, colors = dialogTextFieldColors())
                AppTextField(tags, { tags = it }, label = stringResource(R.string.skills_tags), placeholder = stringResource(R.string.skills_tags_hint), singleLine = false, colors = dialogTextFieldColors())
                if (failed) Text(stringResource(R.string.skills_type_save_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && name.isNotBlank() && (semantics.isNotBlank() || functions.isNotBlank()), onClick = {
                saving = true
                coroutineScope.launch {
                    try {
                        controller.saveSkillType(SkillType(id, name, semantics, functions, tags.lines().map { it.trim() }.filter { it.isNotEmpty() }), scope, expectedWorkspace)
                        onSaved()
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        failed = true
                    } finally { saving = false }
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
