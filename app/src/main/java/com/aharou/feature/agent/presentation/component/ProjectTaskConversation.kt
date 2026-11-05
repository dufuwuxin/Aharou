package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.presentation.AIAgentViewModel
import com.aharou.feature.agent.presentation.AgentUIMessage
import kotlinx.coroutines.CancellationException

private data class ProcessMessages(val messages: List<AgentUIMessage> = emptyList(), val loading: Boolean = true, val error: String? = null)

@Composable
fun ProjectTaskConversation(sessionId: String, agentViewModel: AIAgentViewModel, onToolDetail: (AgentUIMessage) -> Unit) {
    var limit by rememberSaveable(sessionId) { mutableIntStateOf(30) }
    val state by produceState(ProcessMessages(), sessionId, limit) {
        value = ProcessMessages()
        try { agentViewModel.processMessages(sessionId, limit).collect { value = ProcessMessages(it, false) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { value = ProcessMessages(loading = false, error = failure.message ?: "PROCESS_READ_FAILED") }
    }
    val text by remember(sessionId) { agentViewModel.processText(sessionId) }.collectAsStateWithLifecycle(initialValue = null)
    val reasoning by remember(sessionId) { agentViewModel.processReasoning(sessionId) }.collectAsStateWithLifecycle(initialValue = null)
    val tools by remember(sessionId) { agentViewModel.processTools(sessionId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val cache = remember { MarkdownRenderCache() }
    CompositionLocalProvider(LocalToolPreviewOpener provides onToolDetail, LocalSubAgentCardBridge provides agentViewModel.subAgentCardBridge) {
        LazyColumn(Modifier.fillMaxSize().testTag("task_process_messages"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { Text(stringResource(R.string.project_task_error, error), color = MaterialTheme.colorScheme.error) } }
            if (!state.loading && state.error == null && state.messages.isEmpty() && text.isNullOrBlank()) item {
                Text(stringResource(R.string.project_process_no_output), style = MaterialTheme.typography.bodySmall)
            }
            if (state.messages.size >= limit) item {
                TextButton(onClick = { limit += 30 }) { Text(stringResource(R.string.project_process_load_more)) }
            }
            items(state.messages, key = { it.id }) { message ->
                AgentMessageItem(message, markdownCache = cache, showActions = false,
                    liveOutput = tools.firstOrNull { it.messageId == message.id }?.text)
            }
            if (!reasoning.isNullOrBlank()) item { Text(reasoning.orEmpty(), style = MaterialTheme.typography.bodySmall) }
            if (!text.isNullOrBlank()) item { MarkdownContent(text.orEmpty(), MaterialTheme.colorScheme.onSurface, cache = cache) }
        }
    }
}
