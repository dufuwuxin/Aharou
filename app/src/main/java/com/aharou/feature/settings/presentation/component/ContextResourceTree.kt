package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.context.ContextEntry

internal data class ContextResourceRow(val key: String, val depth: Int, val entries: List<ContextEntry>,
                                      val folder: String? = null, val entry: ContextEntry? = null)

internal fun contextResourceRows(entries: List<ContextEntry>, expanded: Set<String>, query: String): List<ContextResourceRow> {
    val selected = entries.distinctBy { it.id }.filter { query.isBlank() ||
        listOf(it.title, it.description, it.folder).any { text -> text.contains(query.trim(), true) } }
    val rows = mutableListOf<ContextResourceRow>()
    for ((identity, scoped) in selected.groupBy { it.scope to it.category }) {
        val root = "${identity.first.name}/${identity.second.name}"
        rows += ContextResourceRow(root, 0, scoped)
        if (root !in expanded && query.isBlank()) continue
        val folders = linkedMapOf<String, List<ContextEntry>>()
        scoped.forEach { entry ->
            val segments = entry.folder.split('/').filter(String::isNotEmpty)
            for (i in segments.indices) {
                val path = segments.take(i + 1).joinToString("/")
                folders.putIfAbsent(path, scoped.filter { it.folder == path || it.folder.startsWith("$path/") })
            }
        }
        val visibleFolders = folders.keys.sorted().filter { path ->
            val parents = path.split('/').dropLast(1).indices.map { index -> path.split('/').take(index + 1).joinToString("/") }
            query.isNotBlank() || parents.all { "$root/folder:$it" in expanded }
        }
        for (entry in scoped.filter { it.folder.isEmpty() }) rows += ContextResourceRow("entry:${entry.id}", 1, listOf(entry), entry = entry)
        for (path in visibleFolders) {
            val key = "$root/folder:$path"
            val depth = path.count { it == '/' } + 1
            rows += ContextResourceRow(key, depth, folders.getValue(path), folder = path)
            if (key in expanded || query.isNotBlank()) {
                scoped.filter { it.folder == path }.forEach { entry ->
                    rows += ContextResourceRow("entry:${entry.id}", depth + 1, listOf(entry), entry = entry)
                }
            }
        }
    }
    return rows
}

@Composable
internal fun ContextResourceTree(entries: List<ContextEntry>, onSelect: (ContextEntry) -> Unit) {
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val rows = remember(entries, expanded, query) { contextResourceRows(entries, expanded, query) }
    Column(Modifier.height(480.dp)) {
        Text(stringResource(R.string.context_resource_tree_note), style = MaterialTheme.typography.bodySmall)
        AppTextField(query, { query = it.take(160) }, placeholder = stringResource(R.string.context_search_placeholder))
        LazyColumn(Modifier.weight(1f)) {
            if (rows.isEmpty()) item { Text(stringResource(R.string.context_folder_empty)) }
            items(rows, key = { it.key }) { row ->
                val first = row.entries.first()
                val label = row.entry?.title ?: row.folder?.substringAfterLast('/') ?:
                    "${stringResource(first.scope.label())} / ${stringResource(first.category.label())}"
                TextButton(onClick = {
                    val entry = row.entry
                    if (entry != null) onSelect(entry)
                    else expanded = if (row.key in expanded) expanded - row.key else expanded + row.key
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Spacing.md * row.depth.coerceAtMost(5))) {
                    Column(Modifier.fillMaxWidth()) {
                        Text((if (row.entry != null) "· " else if (row.key in expanded || query.isNotBlank()) "− " else "+ ") + label,
                            style = MaterialTheme.typography.bodyLarge)
                        if (row.entry != null) Text(stringResource(row.entry.loading.label()), style = MaterialTheme.typography.labelSmall)
                        else Text(row.entries.size.toString(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
