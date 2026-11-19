// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.sandbox

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aharou.R
import com.aharou.feature.agent.domain.binary.SomcpAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Composable
internal fun SomcpFilePreview(item: FileItem, viewModel: SomcpPreviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(item.file) { viewModel.load(item.file) }
    DisposableEffect(viewModel, item.file) { onDispose { viewModel.cancel() } }
    SomcpPreviewContent(state, onLoad = { action, offset, query -> viewModel.load(item.file, action, offset, query) })
}

@Composable
internal fun SomcpPreviewContent(state: SomcpPreviewState, onLoad: (String, Int, String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var license by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp).testTag("somcp_preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.somcp_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.somcp_read_only), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SomcpAnalyzer.ACTIONS.forEach { action -> FilterChip(selected = state.action == action,
                onClick = { onLoad(action, 0, query) }, label = { Text(actionLabel(action)) }) }
        }
        if (state.action != "overview") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it.take(256) }, modifier = Modifier.weight(1f), singleLine = true,
                label = { Text(stringResource(R.string.somcp_filter)) })
            TextButton(onClick = { onLoad(state.action, 0, query) }) { Text(stringResource(R.string.somcp_search)) }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { error ->
            Text(when {
                "ELF_SIZE_LIMIT" in error -> stringResource(R.string.somcp_too_large)
                "NOT_ELF" in error -> stringResource(R.string.somcp_not_elf)
                else -> stringResource(R.string.somcp_failure, error)
            }, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { onLoad(state.action, state.offset, state.query) }) { Text(stringResource(R.string.somcp_retry)) }
        }
        val report = state.report
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (report != null && state.action == "overview") {
                val keys = listOf("architecture", "bits", "endian", "type", "entry", "size_bytes", "section_count",
                    "program_header_count", "symbol_count", "dependencies", "sha256")
                items(keys) { key -> report[key]?.let { value ->
                    SelectionContainer { Text(fieldLabel(key) + ": " + display(value)) }
                } }
            } else if (report != null) {
                val rows = (report["items"] as? JsonArray).orEmpty()
                if (rows.isEmpty()) item { Text(stringResource(R.string.somcp_empty)) }
                items(rows) { row ->
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            (row as? JsonObject)?.forEach { (key, value) -> Text(fieldLabel(key) + ": " + display(value),
                                style = MaterialTheme.typography.bodySmall) }
                            HorizontalDivider()
                        }
                    }
                }
                if (state.action == "strings") item { Text(stringResource(R.string.somcp_strings_bounded), style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (report != null && state.action != "overview") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = state.offset > 0, onClick = { onLoad(state.action, maxOf(0, state.offset - 50), state.query) }) {
                Text(stringResource(R.string.somcp_previous))
            }
            Text(stringResource(R.string.somcp_page, state.offset, report["total"]?.jsonPrimitive?.intOrNull ?: 0),
                style = MaterialTheme.typography.bodySmall)
            val next = (report["next_offset"] as? JsonPrimitive)?.intOrNull
            TextButton(enabled = next != null, onClick = { next?.let { onLoad(state.action, it, state.query) } }) {
                Text(stringResource(R.string.somcp_next))
            }
        }
        TextButton(onClick = { license = true }) { Text(stringResource(R.string.somcp_license)) }
    }
    if (license) SomcpLicenseDialog { license = false }
}

@Composable
private fun SomcpLicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) { listOf("NOTICE", "LICENSE").joinToString("\n\n") { file ->
            context.assets.open("licenses/somcp/$file").bufferedReader().use { it.readText() }
        } }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.somcp_license)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text("SOMCP · ${SomcpAnalyzer.UPSTREAM_COMMIT}")
            Text(text, style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } })
}

@Composable
private fun actionLabel(action: String): String = stringResource(when (action) {
    "sections" -> R.string.somcp_sections; "symbols" -> R.string.somcp_symbols
    "imports" -> R.string.somcp_imports; "exports" -> R.string.somcp_exports
    "dependencies" -> R.string.somcp_dependencies; "strings" -> R.string.somcp_strings
    "relocations" -> R.string.somcp_relocations; else -> R.string.somcp_overview
})

private fun display(value: JsonElement): String = if (value is JsonPrimitive) value.content else value.toString()
@Composable
private fun fieldLabel(key: String): String {
    val resource = when (key) {
        "architecture" -> R.string.somcp_architecture; "bits" -> R.string.somcp_bits; "endian" -> R.string.somcp_endian
        "entry", "address" -> R.string.somcp_address; "size_bytes", "size" -> R.string.somcp_bytes
        "section_count" -> R.string.somcp_section_count; "program_header_count" -> R.string.somcp_program_count
        "symbol_count" -> R.string.somcp_symbol_count; "dependencies" -> R.string.somcp_dependencies
        "name" -> R.string.somcp_name; "offset" -> R.string.somcp_offset; "binding" -> R.string.somcp_binding
        "visibility" -> R.string.somcp_visibility; "section", "section_index" -> R.string.somcp_sections
        "value" -> R.string.somcp_value; "type" -> R.string.somcp_type; "flags" -> R.string.somcp_flags
        "imported" -> R.string.somcp_imports; "exported" -> R.string.somcp_exports
        "original_bytes" -> R.string.somcp_original_bytes; "addend" -> R.string.somcp_addend; else -> null
    }
    return resource?.let { stringResource(it) } ?: key
}
