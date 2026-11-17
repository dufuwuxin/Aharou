package com.aharou.feature.vault.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.vault.VaultRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultKeywordEditor(initial: List<Pair<String, String>>, onDismiss: () -> Unit,
                                onSave: (List<Pair<String, String>>) -> Unit) {
    var rows by remember { mutableStateOf(initial.ifEmpty { listOf("keyword_${java.util.UUID.randomUUID()}" to "") }) }
    val valid = rows.all { VaultRepository.NAME_REGEX.matches(it.first) && it.second.isNotEmpty() } &&
        rows.map { it.first }.distinct().size == rows.size
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.vault_keyword_editor_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.vault_keyword_editor_help), style = MaterialTheme.typography.bodySmall)
            rows.forEachIndexed { index, row ->
                key(row.first) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(stringResource(R.string.vault_keyword_item, index + 1), style = MaterialTheme.typography.labelLarge)
                            AppTextField(row.second, { value -> rows = rows.toMutableList().also { it[index] = row.first to value } },
                                label = stringResource(R.string.vault_keyword_original), singleLine = false, minLines = 2,
                                modifier = Modifier.testTag("keyword_value_$index"))
                            TextButton(onClick = { rows = rows.filterIndexed { i, _ -> i != index } },
                                modifier = Modifier.testTag("keyword_remove_$index")) { Text(stringResource(R.string.common_delete)) }
                        }
                    }
                }
            }
            TextButton(onClick = { rows = rows + ("keyword_${java.util.UUID.randomUUID()}" to "") },
                modifier = Modifier.testTag("keyword_add")) { Text(stringResource(R.string.vault_keyword_add)) }
            Button(enabled = valid, onClick = { onSave(rows) }, modifier = Modifier.testTag("keyword_save")) {
                Text(stringResource(R.string.common_save))
            }
        }
    }
}
