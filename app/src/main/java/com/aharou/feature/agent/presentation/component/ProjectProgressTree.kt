package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.feature.agent.presentation.AIAgentViewModel
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.MessageRole

/** Public conversation and tool receipts only; never derive or render private reasoning. */
@Composable
internal fun ProjectProgressTree(messages: List<AgentUIMessage>, liveTools: List<AgentUIMessage>, liveText: String, running: Boolean = false) {
    val nodes = projectProgressNodes(messages, liveTools, liveText, running)
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    Column {
        Text(stringResource(R.string.project_progress_tree_title), style = MaterialTheme.typography.labelLarge)
        nodes.forEach { node ->
            key(node.id) {
                Column(Modifier.padding(start = if (node.kind == "目标") Spacing.xs else Spacing.md)) {
                    TextButton(onClick = { expanded = if (node.id in expanded) expanded - node.id else expanded + node.id }) {
                        Text("${node.kind} · ${node.text.lineSequence().firstOrNull().orEmpty().take(72)}", maxLines = 2)
                    }
                    if (node.id in expanded) Text(node.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}


/** Subscribe to the existing main/member message and tool streams; no new execution engine. */
@Composable
internal fun ProjectProgressConversation(sessionId: String, agent: AIAgentViewModel,
    original: @Composable () -> Unit) {
    var showOriginal by rememberSaveable(sessionId) { mutableStateOf(false) }
    var readError by remember(sessionId) { mutableStateOf(false) }
    val records by produceState<List<AgentUIMessage>>(emptyList(), sessionId) {
        try { agent.processMessages(sessionId, 300).collect { value = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { readError = true }
    }
    val text by remember(sessionId) { agent.processText(sessionId) }.collectAsStateWithLifecycle(initialValue = null)
    val tools by remember(sessionId) { agent.processTools(sessionId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val active by agent.activeAgentSessions.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { showOriginal = !showOriginal }) { Text(stringResource(if (showOriginal) R.string.project_original_collapse else R.string.project_original_expand)) }
        if (readError) Text(stringResource(R.string.project_progress_unavailable), color = MaterialTheme.colorScheme.error)
        if (showOriginal) Box(Modifier.weight(1f)) { original() }
        else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ProjectProgressTree(records, projectLiveToolMessages(records, tools), text.orEmpty(), sessionId in active)
        }
    }
}
