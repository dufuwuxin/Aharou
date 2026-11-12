package com.aharou.feature.settings.presentation.component

import android.content.ClipData
import android.content.Intent
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.editor.presentation.EditorSettingsScreen
import com.aharou.feature.settings.presentation.ToolboxMessage
import com.aharou.feature.settings.presentation.ToolboxUiState
import com.aharou.feature.settings.presentation.ToolboxViewModel

@Composable
internal fun ToolboxSection(
    viewModel: ToolboxViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    onOpenEditor: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ToolboxScreen(state, viewModel, onOpenEditor = onOpenEditor)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolboxScreen(
    state: ToolboxUiState, viewModel: ToolboxViewModel,
    onOpenEditor: (() -> Unit)?,
) {
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(stringResource(R.string.tools_runner_hint), style = MaterialTheme.typography.bodySmall)
        val selected = state.selected
        if (selected != null && state.selectionReady) {
            SettingsGroupHeader(text = selected.name)
            onOpenEditor?.let { edit ->
                TextButton(onClick = edit) { Text(stringResource(R.string.tools_edit_definition)) }
            }
            Text(selected.description, style = MaterialTheme.typography.bodySmall)
            selected.parameters.forEach { p ->
                AppTextField(
                    value = state.arguments[p.name].orEmpty(),
                    onValueChange = { viewModel.setArgument(p.name, it) },
                    label = "${p.name}: ${p.type.jsonType}${if (p.required) " *" else ""}",
                    supportingText = { Text(p.description) },
                )
            }
            Text(stringResource(R.string.toolbox_risk, selected.timeoutSeconds), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.toolbox_preview), style = MaterialTheme.typography.labelLarge)
            TechnicalOutput(state.preview.ifBlank { selected.command })
            state.validation?.let {
                Text(stringResource(R.string.toolbox_invalid_arguments, it), color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = viewModel::run, enabled = !state.running && state.validation == null && state.preview.isNotBlank()) {
                    Text(stringResource(R.string.toolbox_run))
                }
                TextButton(onClick = viewModel::exportSelected) { Text(stringResource(R.string.toolbox_export)) }
            }
        }
        if (!state.selectionReady) Text(stringResource(R.string.tools_history_hint))
        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.common_cancel)) }
        }
        if (state.runRoot.isNotBlank()) Text(stringResource(R.string.toolbox_root, state.runRoot), style = MaterialTheme.typography.bodySmall)
        if (state.runRoot.isNotBlank() && !state.running) {
            Text(stringResource(R.string.toolbox_exit_code, state.exitCode?.toString() ?: stringResource(R.string.toolbox_unknown)))
            state.errorCode?.let { TechnicalOutput(it) }
        }
        if (state.output.isNotBlank()) {
            SettingsGroupHeader(text = stringResource(R.string.toolbox_result))
            TechnicalOutput(state.output)
        }
        if (state.truncated) Text(stringResource(R.string.toolbox_truncated), color = MaterialTheme.colorScheme.error)
        state.message?.let { message ->
            Text(stringResource(when (message) {
                ToolboxMessage.INVALID -> R.string.toolbox_invalid_json
                ToolboxMessage.CONFLICT -> R.string.toolbox_conflict
                ToolboxMessage.SAVE_FAILED -> R.string.toolbox_save_failed
                ToolboxMessage.SAVED -> R.string.toolbox_saved
                ToolboxMessage.LOAD_FAILED -> R.string.toolbox_load_failed
                ToolboxMessage.CANCELLED -> R.string.toolbox_cancelled
            }))
        }
        if (state.exportText.isNotBlank()) {
            SettingsGroupHeader(text = stringResource(R.string.toolbox_export))
            TechnicalOutput(state.exportText)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = {
                    copyScope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", state.exportText)))
                    }
                }) { Text(stringResource(R.string.toolbox_copy)) }
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, state.exportText)
                    }, null))
                }) { Text(stringResource(R.string.toolbox_share)) }
            }
        }
    }
 }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolImportSheet(
    visible: Boolean,
    viewModel: ToolboxViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val savingNow by rememberUpdatedState(state.saving)
    if (visible) {
        AdaptiveModalBottomSheet(onDismissRequest = { if (!state.saving) onDismiss() },
            sheetGesturesEnabled = !state.saving,
            sheetState = rememberModalBottomSheetState(confirmValueChange = { !savingNow })) {
            Column(
                Modifier.imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(stringResource(R.string.toolbox_import_hint))
                AppTextField(value = state.importText, onValueChange = viewModel::setImportText,
                    label = stringResource(R.string.toolbox_import), singleLine = false, maxLines = 12)
                TextButton(onClick = viewModel::previewImport) { Text(stringResource(R.string.toolbox_preview_import)) }
                state.importDraft?.let { draft ->
                    TechnicalOutput(state.importText)
                    Text(draft.name, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.toolbox_risk, draft.timeoutSeconds))
                    Button(onClick = viewModel::saveImport, enabled = !state.saving) {
                        Text(stringResource(R.string.toolbox_save_import))
                    }
                }
                state.message?.let { message ->
                    Text(stringResource(when (message) {
                        ToolboxMessage.SAVED -> R.string.toolbox_saved
                        ToolboxMessage.CONFLICT -> R.string.toolbox_conflict
                        ToolboxMessage.SAVE_FAILED -> R.string.toolbox_save_failed
                        else -> R.string.toolbox_invalid_json
                    }))
                }
            }
        }
    }
}

@Composable
private fun TechnicalOutput(text: String) {
    SelectionContainer {
        Text(text, modifier = Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
    }
}
