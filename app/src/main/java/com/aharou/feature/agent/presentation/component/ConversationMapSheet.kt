package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.model.ChatSession
import com.aharou.feature.agent.presentation.ChatMessagesState
import com.aharou.feature.agent.presentation.ConversationMapNode
import com.aharou.feature.agent.presentation.projectConversationMap
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsRow
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationMapSheet(
    sessionId: String?,
    session: ChatSession?,
    messagesState: ChatMessagesState,
    bodyVisible: Boolean,
    canLocate: (String) -> Boolean,
    onJump: (String) -> Boolean,
    onDismiss: () -> Unit,
    embedded: Boolean = false,
) {
    val ready = sessionId != null && messagesState.loaded && messagesState.sessionId == sessionId
    val matchedSession = session?.takeIf { it.id == sessionId }
    val projection = remember(ready, messagesState.messages, messagesState.hasMore) {
        projectConversationMap(if (ready) messagesState.messages else emptyList(), messagesState.hasMore)
    }
    var query by remember(sessionId) { mutableStateOf("") }
    var detailsExpanded by remember(sessionId) { mutableStateOf(false) }
    var jumpUnavailable by remember(sessionId) { mutableStateOf(false) }
    val nodes = remember(projection, query) {
        projection.nodes.withIndex().filter { query.isBlank() || it.value.userSummary.orEmpty().contains(query.trim(), ignoreCase = true) }
    }
    val expandedState = stringResource(if (detailsExpanded) R.string.conversation_map_expanded else R.string.conversation_map_collapsed)
    SessionDiagnosticContainer(embedded, onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(if (embedded) 1f else 0.88f).imePadding().testTag("conversation_map_sheet")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.conversation_map_title), Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.conversation_map_close))
                }
            }
            Text(stringResource(if (ready && !projection.hasMore) R.string.conversation_map_loaded else R.string.conversation_map_partial),
                Modifier.padding(horizontal = Spacing.lg), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppTextField(value = query, onValueChange = { query = it.take(160) },
                label = stringResource(R.string.conversation_map_search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm))
            LazyColumn(Modifier.weight(1f).testTag("conversation_map_nodes"),
                contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                item(key = "details") {
                    SettingsGroup {
                        SettingsRow(title = stringResource(R.string.conversation_map_details),
                            onClick = { detailsExpanded = !detailsExpanded },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = expandedState },
                            trailing = { Text(expandedState, style = MaterialTheme.typography.labelMedium) })
                        if (detailsExpanded) {
                            ConversationSessionDetails(matchedSession, if (ready) projection.loadedMessageCount else null)
                        }
                    }
                }
                item(key = "status") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.conversation_map_counts, nodes.size, projection.nodes.size, projection.loadedMessageCount),
                            style = MaterialTheme.typography.labelLarge)
                        if (!bodyVisible) Text(stringResource(R.string.conversation_map_project_readonly), style = MaterialTheme.typography.bodyMedium)
                        if (jumpUnavailable) Text(stringResource(R.string.conversation_map_unavailable), color = MaterialTheme.colorScheme.error)
                        if (!ready) Text(stringResource(if (sessionId == null) R.string.conversation_map_empty else R.string.conversation_map_loading))
                        else if (nodes.isEmpty()) Text(stringResource(if (projection.nodes.isEmpty()) R.string.conversation_map_empty else R.string.conversation_map_no_results))
                    }
                }
                itemsIndexed(nodes, key = { _, node -> "node:${node.value.id}" }) { index, indexed ->
                    ConversationNodeCard(indexed.value, indexed.index + 1, index < nodes.lastIndex,
                        enabled = bodyVisible && ready && canLocate(indexed.value.startMessageId),
                        onClick = {
                            if (onJump(indexed.value.startMessageId)) onDismiss() else jumpUnavailable = true
                        })
                }
            }
        }
    }
}

@Composable
private fun ConversationNodeCard(node: ConversationMapNode, number: Int, connected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(number.toString(), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
            }
            if (connected) Box(Modifier.width(2.dp).height(Spacing.xl).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Card(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(Radius.mdLarge)) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(if (node.userSummary == null) R.string.conversation_map_prelude else R.string.conversation_map_round, number),
                    style = MaterialTheme.typography.labelLarge)
                Text(node.userSummary?.ifBlank { null } ?: stringResource(R.string.conversation_map_no_text),
                    style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.conversation_map_node_counts, node.messageCount, node.assistantCount, node.toolCount),
                    style = MaterialTheme.typography.bodySmall)
                Text(formatConversationTime(node.timestamp), style = MaterialTheme.typography.bodySmall)
                if (!enabled) Text(stringResource(R.string.conversation_map_unavailable), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ConversationSessionDetails(session: ChatSession?, loadedCount: Int?) {
    val unavailable = stringResource(R.string.conversation_map_unknown)
    if (session == null) {
        SettingsRow(title = stringResource(R.string.conversation_map_unknown))
        return
    }
    SettingsRow(title = stringResource(R.string.conversation_map_session_title), subtitle = session.title.ifBlank { unavailable })
    SettingsRow(title = stringResource(R.string.conversation_map_created), subtitle = formatConversationTime(session.createdAt))
    SettingsRow(title = stringResource(R.string.conversation_map_updated), subtitle = formatConversationTime(session.updatedAt))
    SettingsRow(title = stringResource(R.string.conversation_map_workspace), subtitle = session.workspacePath.ifBlank { unavailable })
    SettingsRow(title = stringResource(R.string.conversation_map_mode), subtitle = session.mode.name)
    SettingsRow(title = stringResource(R.string.conversation_map_model), subtitle = session.model?.ifBlank { null } ?: unavailable)
    SettingsRow(title = stringResource(R.string.conversation_map_provider), subtitle = session.providerId?.ifBlank { null } ?: unavailable)
    SettingsRow(title = stringResource(R.string.conversation_map_messages), subtitle = loadedCount?.toString() ?: unavailable)
    SettingsRow(title = stringResource(R.string.conversation_map_tokens),
        subtitle = stringResource(R.string.conversation_map_token_counts, session.totalInputTokens, session.totalOutputTokens))
}

private fun formatConversationTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
