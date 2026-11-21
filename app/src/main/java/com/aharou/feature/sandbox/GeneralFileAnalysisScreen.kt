package com.aharou.feature.sandbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aharou.R
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GeneralFileAnalysisScreen(item: FileItem, onBack: () -> Unit,
    viewModel: SomcpPreviewViewModel = hiltViewModel(key = "general-file-analysis")) {
    val state by viewModel.state.collectAsState()
    var specialized by remember(item.file) { mutableStateOf(false) }
    var previous by remember(item.file) { mutableStateOf(emptyList<Int>()) }
    LaunchedEffect(item.file) { viewModel.load(item.file, general = true) }
    DisposableEffect(item.file, viewModel) { onDispose { viewModel.cancel() } }
    val back = { if (specialized) specialized = false else onBack() }
    BackHandler(onBack = back)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.file_analysis_title, item.name)) },
        navigationIcon = { TextButton(onClick = back) { Text(stringResource(R.string.common_back)) } }) }) { padding ->
        if (specialized) Box(Modifier.fillMaxSize().padding(padding)) { SomcpFilePreview(item) }
        else GeneralFileAnalysisContent(state, Modifier.fillMaxSize().padding(padding), onElf = { specialized = true },
            onLoad = { action, next ->
                previous = when {
                    action != state.action || next == 0 -> emptyList()
                    next == state.offset -> previous
                    else -> previous + state.offset
                }
                viewModel.load(item.file, action, next, general = true)
            }, onPrevious = {
                previous.lastOrNull()?.let { offset ->
                    previous = previous.dropLast(1); viewModel.load(item.file, state.action, offset, general = true)
                }
            }, hasPrevious = previous.isNotEmpty())
    }
}

@Composable
internal fun GeneralFileAnalysisContent(state: SomcpPreviewState, modifier: Modifier = Modifier,
    onElf: () -> Unit, onLoad: (String, Int) -> Unit, onPrevious: () -> Unit, hasPrevious: Boolean) {
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.file_analysis_read_only), style = MaterialTheme.typography.bodySmall)
        val report = state.report
        val format = report?.get("format")?.jsonPrimitive?.content.orEmpty()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onLoad("overview", 0) }) { Text(stringResource(R.string.somcp_overview)) }
            if (format.startsWith("Text/")) TextButton(onClick = { onLoad("text", 0) }) { Text(stringResource(R.string.file_analysis_text)) }
            if (format.startsWith("ZIP")) TextButton(onClick = { onLoad("archive", 0) }) { Text(stringResource(R.string.file_analysis_archive)) }
            if (format == "ELF") TextButton(onClick = onElf) { Text(stringResource(R.string.somcp_title)) }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(stringResource(R.string.file_analysis_error, it), color = MaterialTheme.colorScheme.error) }
        val archive = report?.get("archive") as? JsonObject
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (report != null) {
                if (state.action == "overview") {
                    item { Text(stringResource(R.string.file_analysis_format, format)) }
                    item { Text(stringResource(R.string.file_analysis_size, report["size_bytes"]!!.jsonPrimitive.long)) }
                    item { SelectionContainer { Text("SHA-256: " + report["sha256"]!!.jsonPrimitive.content) } }
                    item { Text(stringResource(R.string.file_analysis_capabilities, report["supported_analysis"]!!.jsonPrimitive.content)) }
                    item { SelectionContainer { Text(report["header_hex"]!!.jsonPrimitive.content) } }
                    (report["text_preview"] as? JsonPrimitive)?.let { preview -> item { SelectionContainer { Text(preview.content) } } }
                } else if (state.action == "text") item { SelectionContainer { Text(report["text"]!!.jsonPrimitive.content) } }
                else if (archive != null) {
                    val rows = (archive["items"] as? JsonArray).orEmpty()
                    if (rows.isEmpty()) item { Text(stringResource(R.string.somcp_empty)) }
                    items(rows) { raw ->
                        val row = raw.jsonObject
                        SelectionContainer { Column {
                            Text(row["name"]!!.jsonPrimitive.content)
                            Text(stringResource(R.string.file_analysis_archive_sizes,
                                (row["compressed_bytes"] as? JsonPrimitive)?.content ?: "?",
                                (row["uncompressed_bytes"] as? JsonPrimitive)?.content ?: "?"), style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        } }
                    }
                }
            }
        }
        if (report != null && state.action != "overview") {
            val page = archive ?: report
            val next = (page["next_offset"] as? JsonPrimitive)?.intOrNull
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = hasPrevious, onClick = onPrevious) { Text(stringResource(R.string.somcp_previous)) }
                TextButton(enabled = next != null, onClick = { next?.let { onLoad(state.action, it) } }) { Text(stringResource(R.string.somcp_next)) }
            }
        }
        TextButton(enabled = !state.loading, onClick = { onLoad(state.action, state.offset) }) { Text(stringResource(R.string.somcp_retry)) }
    }
}
