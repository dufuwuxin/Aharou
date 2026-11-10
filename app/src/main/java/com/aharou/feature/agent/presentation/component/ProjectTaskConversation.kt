package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.presentation.AIAgentViewModel
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.MessageRole
import kotlinx.coroutines.CancellationException

private data class ProcessMessages(val messages: List<AgentUIMessage> = emptyList(), val loading: Boolean = true, val error: String? = null)

@Composable
fun ProjectTaskConversation(sessionId: String, agentViewModel: AIAgentViewModel, onToolDetail: (AgentUIMessage) -> Unit) {
    key(sessionId) {
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
    val cache = remember(sessionId) { MarkdownRenderCache() }
    val activeSessions by agentViewModel.activeAgentSessions.collectAsStateWithLifecycle()
    var previousRounds by remember(sessionId) { mutableStateOf(emptyList<ProjectConversationRound>()) }
    val rounds = remember(state.messages, previousRounds) { projectConversationRounds(state.messages, previousRounds) }
    SideEffect { if (!state.loading) previousRounds = rounds }
    val live = sessionId in activeSessions
    val finalReply = projectFinalReply(rounds, live || state.loading || state.error != null)
    val liveTools = projectLiveToolMessages(state.messages, if (live) tools else emptyList())
    val liveToolIds = liveTools.mapTo(mutableSetOf()) { it.id }
    var summaryExpanded by rememberSaveable(sessionId) { mutableStateOf(true) }
    var expandedRounds by rememberSaveable(sessionId) { mutableStateOf(emptyMap<String, Boolean>()) }
    var reasoningExpanded by rememberSaveable(sessionId) { mutableStateOf(emptyMap<String, Boolean>()) }
    var toolsExpanded by rememberSaveable(sessionId) { mutableStateOf(emptyMap<String, Boolean>()) }
    CompositionLocalProvider(LocalToolPreviewOpener provides onToolDetail, LocalSubAgentCardBridge provides agentViewModel.subAgentCardBridge) {
        LazyColumn(Modifier.fillMaxSize().testTag("task_process_messages"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item(key = "summary") {
                TextButton(onClick = { summaryExpanded = !summaryExpanded }) { Text(stringResource(R.string.project_process_summary)) }
                if (summaryExpanded) {
                    if (finalReply == null) Text(stringResource(R.string.project_process_no_summary))
                    else AgentMessageItem(finalReply, markdownCache = cache, reasoningVisible = false)
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { Text(stringResource(R.string.project_task_error, error), color = MaterialTheme.colorScheme.error) } }
            if (!state.loading && state.error == null && state.messages.isEmpty() && !live) item {
                Text(stringResource(R.string.project_process_no_output), style = MaterialTheme.typography.bodySmall)
            }
            if (state.messages.size >= limit) item {
                TextButton(onClick = { limit += 30 }) { Text(stringResource(R.string.project_process_load_more)) }
            }
            if (live) item(key = "live") {
                Text(stringResource(R.string.project_process_live), style = MaterialTheme.typography.labelMedium)
                if (!text.isNullOrBlank() || !reasoning.isNullOrBlank())
                    AgentMessageItem(AgentUIMessage(id = "live:$sessionId", role = MessageRole.ASSISTANT,
                        content = text.orEmpty(), reasoning = reasoning), markdownCache = cache,
                        reasoningExpandedOverride = reasoningExpanded["live:$sessionId"] ?: true,
                        onReasoningExpandedChange = { reasoningExpanded = reasoningExpanded + ("live:$sessionId" to it) })
            }
            items(liveTools, key = { "live-tool:${it.id}" }) { tool ->
                AgentMessageItem(tool, markdownCache = cache, liveOutput = tool.content,
                    toolExpandedOverride = toolsExpanded[tool.id] ?: true,
                    onToolExpandedChange = { toolsExpanded = toolsExpanded + (tool.id to it) })
            }
            rounds.forEachIndexed { index, round ->
                item(key = "round:${round.id}") {
                    TextButton(onClick = {
                        expandedRounds = expandedRounds + (round.id to !(expandedRounds[round.id] ?: (index == rounds.lastIndex)))
                    }) { Text(stringResource(R.string.project_process_round, index + 1)) }
                }
                if (expandedRounds[round.id] ?: (index == rounds.lastIndex)) {
                    item(key = "reasoning:${round.id}") {
                        Text(stringResource(R.string.project_process_reasoning), style = MaterialTheme.typography.labelMedium)
                        if (round.messages.none { it.role == MessageRole.ASSISTANT && !it.reasoning.isNullOrBlank() })
                            Text(stringResource(R.string.project_process_no_reasoning), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.project_process_reply), style = MaterialTheme.typography.labelMedium)
                    }
                    items(round.messages.filterNot { it.id in liveToolIds }, key = { "message:${it.id}" }) { message ->
                        AgentMessageItem(message, markdownCache = cache, showActions = false,
                            reasoningExpandedOverride = reasoningExpanded[message.id] ?: true,
                            onReasoningExpandedChange = { reasoningExpanded = reasoningExpanded + (message.id to it) },
                            toolExpandedOverride = toolsExpanded[message.id] ?: true,
                            onToolExpandedChange = { toolsExpanded = toolsExpanded + (message.id to it) })
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun ProjectRootConversation(records: List<String>, agentViewModel: AIAgentViewModel,
    onToolDetail: (AgentUIMessage) -> Unit) {
    val active by agentViewModel.activeAgentSessions.collectAsStateWithLifecycle()
    var requested by rememberSaveable { mutableStateOf<String?>(null) }
    val recordState = rememberSaveableStateHolder()
    val populated = records.filter { id ->
        key(id) {
            var probeLimit by remember(id) { mutableIntStateOf(30) }
            val messages by produceState<List<AgentUIMessage>>(emptyList(), id, probeLimit) {
                try { agentViewModel.processMessages(id, probeLimit).collect { value = it } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { value = emptyList() }
            }
            val hasOutput = messages.any { !it.isBackgroundNotification && !it.isContextSummary && !it.isCompactionMarker &&
                (it.role == MessageRole.TOOL || it.role == MessageRole.ASSISTANT &&
                    (it.content.isNotBlank() || !it.reasoning.isNullOrBlank())) }
            LaunchedEffect(messages, probeLimit) {
                if (!hasOutput && messages.size >= probeLimit) probeLimit += 30
            }
            hasOutput
        }
    }
    val selected = requested?.takeIf { it in records } ?: records.firstOrNull { it in active }
        ?: populated.firstOrNull() ?: records.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            records.forEachIndexed { index, id ->
                FilterChip(selected = id == selected, onClick = { requested = id },
                    modifier = Modifier.testTag("project_root_record_$id"),
                    label = { Text(stringResource(R.string.project_main_record, index + 1)) })
            }
        }
        if (selected != null) Box(Modifier.weight(1f)) {
            recordState.SaveableStateProvider(selected) {
                key(selected) { ProjectTaskConversation(selected, agentViewModel, onToolDetail) }
            }
        } else Text(stringResource(R.string.project_process_no_output))
    }
}
