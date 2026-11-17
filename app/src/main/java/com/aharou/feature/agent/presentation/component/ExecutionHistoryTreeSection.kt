package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.context.ContextLibraryPlanner
import com.aharou.feature.agent.domain.tool.ExecutionHistoryEvent
import com.aharou.feature.agent.presentation.SessionExecutionHistory
import com.aharou.feature.agent.presentation.executionTreeJumpAllowed
import com.aharou.feature.agent.presentation.filterExecutionEvents
import com.aharou.feature.settings.presentation.component.SettingsGroup
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ExecutionHistoryTreeSection(
    sessionId: String?, history: SessionExecutionHistory, bodyVisible: Boolean,
    canLocate: (String) -> Boolean, onJump: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val ready = sessionId != null && sessionId == history.sessionId && history.loaded
    var query by remember(sessionId) { mutableStateOf("") }
    var expanded by remember(sessionId) { mutableStateOf(emptySet<String>()) }
    var jumpUnavailable by remember(sessionId) { mutableStateOf(false) }
    var sourceId by remember(sessionId) { mutableStateOf<String?>(null) }
    if (ready) sourceId?.let(history.sources::get)?.let { source ->
        ExecutionHistorySourceSheet(source) { sourceId = null }
    }
    val groups = remember(history, query, ready) {
        if (!ready) emptyMap() else filterExecutionEvents(history.tree.events, query).groupBy { it.groupKey }
    }
    Column(modifier.testTag("execution_history_tree"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(if (!ready || history.hasMore) R.string.execution_tree_partial else R.string.conversation_map_loaded),
            Modifier.padding(horizontal = Spacing.lg), style = MaterialTheme.typography.bodySmall)
        AppTextField(value = query, onValueChange = { query = it.take(160) },
            label = stringResource(R.string.execution_tree_search), modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg))
        if (jumpUnavailable) Text(stringResource(R.string.execution_tree_jump_unavailable),
            Modifier.padding(horizontal = Spacing.lg), color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (groups.isEmpty()) item { Text(stringResource(R.string.execution_tree_empty)) }
            items(groups.entries.toList(), key = { it.key }) { (groupKey, events) ->
                val open = groupKey in expanded
                SettingsGroup {
                    TextButton(onClick = { expanded = if (open) expanded - groupKey else expanded + groupKey },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("execution_group_${events.first().key}")) {
                        val event = events.first()
                        Text(stringResource(R.string.execution_tree_group, if (open) "−" else "+",
                            treeLabel(event.path ?: "${event.category}/${event.action}"), events.size),
                            Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                    }
                    if (open) events.forEach { event ->
                        androidx.compose.runtime.key(event.key) {
                            ExecutionEventRow(event, history, ready, canLocate) { anchor ->
                                if (ready && anchor != null && history.sources.containsKey(anchor)) {
                                    sourceId = anchor
                                    jumpUnavailable = false
                                } else if (!executionTreeJumpAllowed(sessionId, history.sessionId, ready, bodyVisible, anchor, canLocate) ||
                                    anchor == null || !onJump(anchor)) jumpUnavailable = true
                                else jumpUnavailable = false
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun treeLabel(text: String): String = ContextLibraryPlanner.safe(text).take(512)
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").filterNot { it.isISOControl() }

@Composable
private fun ExecutionEventRow(event: ExecutionHistoryEvent, history: SessionExecutionHistory, ready: Boolean,
                              canLocate: (String) -> Boolean, onJump: (String?) -> Unit) {
    val status = stringResource(when (event.status) {
        "success" -> R.string.execution_tree_success
        "error" -> R.string.execution_tree_error
        "pending" -> R.string.execution_tree_pending
        else -> R.string.execution_tree_unknown
    })
    val timestamp = (event.assistantMessageId ?: event.toolMessageId)?.let(history.timestamps::get)
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Text(stringResource(R.string.execution_tree_event, event.position + 1, treeLabel(event.action), status),
            style = MaterialTheme.typography.bodyMedium)
        timestamp?.let { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(stringResource(R.string.execution_tree_call, treeLabel(event.callId)), style = MaterialTheme.typography.labelSmall)
        listOf(R.string.execution_tree_user to event.userMessageId, R.string.execution_tree_assistant to event.assistantMessageId,
            R.string.execution_tree_receipt to event.toolMessageId).forEach { (title, anchor) ->
            val available = ready && anchor != null && (history.sources.containsKey(anchor) || canLocate(anchor))
            Text(stringResource(title, anchor?.let(::treeLabel) ?: "—"),
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onJump(anchor) }
                    .padding(vertical = Spacing.sm),
                style = MaterialTheme.typography.labelMedium,
                color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
