package com.aharou.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors

@Composable
internal fun PluginPromptEditor(draft: PluginPromptDraft, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var content by rememberSaveable(draft.id, draft.workspace, draft.version) { mutableStateOf(draft.content) }
    AlertDialog(
        onDismissRequest = { if (!draft.saving) onDismiss() },
        title = { Text(stringResource(R.string.plugin_injection_edit)) },
        text = {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(stringResource(R.string.plugin_injection_help))
                AppTextField(content, { content = it }, label = stringResource(R.string.plugin_injection_edit),
                    enabled = !draft.saving, singleLine = false, minLines = 5, colors = dialogTextFieldColors())
                if (draft.failed) Text(stringResource(R.string.plugin_injection_failed), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !draft.saving, onClick = { onSave(null) }) {
                    Text(stringResource(R.string.plugin_injection_reset))
                }
            }
        },
        confirmButton = { TextButton(enabled = !draft.saving && content.length <= 40000, onClick = { onSave(content) }) {
            Text(stringResource(R.string.common_save))
        } },
        dismissButton = { TextButton(enabled = !draft.saving, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
