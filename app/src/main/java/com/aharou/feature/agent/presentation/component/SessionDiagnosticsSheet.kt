package com.aharou.feature.agent.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import com.aharou.feature.agent.domain.model.ChatSession
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.agent.presentation.ChatMessagesState
import com.aharou.feature.agent.presentation.observe.ObservePanelViewModel
import com.aharou.feature.agent.presentation.observe.ObserveScreen
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsRow

internal data class SessionContextMetrics(
    val used: Int?, val budget: Int?, val estimated: Boolean, val currentModelBudget: Int?,
) {
    val remaining: Int? get() = budget?.let { limit -> used?.let { (limit - it).coerceAtLeast(0) } }
    val progress: Float? get() = budget?.let { limit -> used?.let { (it.toFloat() / limit).coerceIn(0f, 1f) } }
}

internal fun diagnosticSessionId(current: String?, projectBody: Boolean, bound: String?): String? =
    current?.takeIf { !projectBody || it == bound }

internal fun sessionContextMetrics(usage: AgentEvent.ContextUsage?, lastInputTokens: Int, modelBudget: Int) =
    SessionContextMetrics(
        used = usage?.inputTokens?.takeIf { it >= 0 } ?: lastInputTokens.takeIf { it > 0 },
        budget = usage?.inputBudget?.takeIf { it > 0 },
        estimated = usage?.estimated ?: false,
        currentModelBudget = modelBudget.takeIf { it > 0 },
    )

internal enum class SessionDiagnosticTab(@param:StringRes val title: Int) {
    CONTEXT(R.string.session_diagnostics_context),
    PLANNING(R.string.project_main_conversation),
    CONVERSATION(R.string.conversation_map_title),
    ACTIVITY(R.string.activity_sheet_title),
    MEMBERS(R.string.session_diagnostics_members),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDiagnosticContainer(embedded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (embedded) content() else AdaptiveModalBottomSheet(onDismissRequest = onDismiss) { content() }
}

@Composable
internal fun SessionContextStatus(metrics: SessionContextMetrics, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("project_context_status").clickable(onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.session_diagnostics_title), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(if (metrics.used == null) stringResource(R.string.session_context_no_usage)
                else if (metrics.budget == null) stringResource(R.string.session_context_used_only, metrics.used)
                else stringResource(R.string.session_context_usage, metrics.used, metrics.budget),
                style = MaterialTheme.typography.labelMedium)
        }
        Text(if (metrics.estimated) stringResource(R.string.session_context_estimated)
            else stringResource(R.string.session_context_open), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        metrics.progress?.let { progress ->
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDiagnosticsSheet(
    sessionId: String?, session: ChatSession?, messagesState: ChatMessagesState,
    receipts: List<ToolCallReceiptEntity>, metrics: SessionContextMetrics, injectedText: String?,
    bodyVisible: Boolean, canLocate: (String) -> Boolean, onJump: (String) -> Boolean,
    onDismiss: () -> Unit,
    projectConversation: (@Composable () -> Unit)? = null,
) {
    val sections = SessionDiagnosticTab.entries.filter { it != SessionDiagnosticTab.PLANNING || projectConversation != null }
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).testTag("session_diagnostics")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                Text(stringResource(R.string.session_diagnostics_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("diagnostics_close")) {
                    Text(stringResource(R.string.conversation_map_close))
                }
            }
            key(sessionId, projectConversation != null) {
                var expanded by remember { mutableStateOf(setOf(SessionDiagnosticTab.CONTEXT)) }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Embedded readers contain weighted/lazy lists and need a finite viewport.
                    val readerHeight = maxHeight
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            .testTag("diagnostics_sections").padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        sections.forEach { section ->
                            val isExpanded = section in expanded
                            val expandedState = stringResource(if (isExpanded) R.string.conversation_map_expanded else R.string.conversation_map_collapsed)
                            SettingsGroup {
                                SettingsRow(
                                    title = stringResource(section.title),
                                    onClick = { expanded = if (isExpanded) expanded - section else expanded + section },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                        .testTag("diagnostics_section_${section.name}")
                                        .semantics { stateDescription = expandedState },
                                    trailing = { Text(stringResource(if (isExpanded) R.string.common_collapse_action else R.string.common_expand),
                                        style = MaterialTheme.typography.labelMedium) },
                                )
                                if (isExpanded) {
                                    val contentModifier = Modifier.fillMaxWidth().testTag("diagnostics_content_${section.name}")
                                    when (section) {
                                        SessionDiagnosticTab.CONTEXT -> Column(contentModifier) {
                                            SessionContextStatus(metrics, {})
                                            Text(stringResource(R.string.session_context_budget_note), Modifier.padding(horizontal = Spacing.lg),
                                                style = MaterialTheme.typography.bodySmall)
                                            metrics.remaining?.let { Text(stringResource(R.string.session_context_remaining, it),
                                                Modifier.padding(horizontal = Spacing.lg)) }
                                            metrics.currentModelBudget?.let { Text(stringResource(R.string.session_context_model_budget, it),
                                                Modifier.padding(horizontal = Spacing.lg), style = MaterialTheme.typography.bodySmall) }
                                            if (injectedText == null) Text(stringResource(R.string.session_context_no_snapshot), Modifier.padding(Spacing.lg))
                                            else InjectedContextSheet(injectedText, onDismiss, embedded = true)
                                        }
                                        SessionDiagnosticTab.ACTIVITY -> Box(contentModifier) {
                                            ActivityTimelineSheet(receipts.filter { it.sessionId == sessionId }, onDismiss, embedded = true)
                                        }
                                        else -> Box(contentModifier.height(readerHeight)) {
                                            when (section) {
                                                SessionDiagnosticTab.PLANNING -> projectConversation?.invoke()
                                                SessionDiagnosticTab.CONVERSATION -> ConversationMapSheet(sessionId, session, messagesState,
                                                    bodyVisible, canLocate, onJump, onDismiss, embedded = true)
                                                SessionDiagnosticTab.MEMBERS -> SessionMembersDiagnostics(sessionId, onDismiss)
                                                else -> Unit
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionMembersDiagnostics(sessionId: String?, onDismiss: () -> Unit) {
    if (sessionId == null) {
        Text(stringResource(R.string.session_context_no_usage), Modifier.padding(Spacing.lg))
        return
    }
    val vm: ObservePanelViewModel = hiltViewModel(key = "session-diagnostics-$sessionId")
    LaunchedEffect(sessionId) { vm.bind(sessionId) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    if (state.sessionId == sessionId) ObserveScreen(state, vm::setDirection, vm::setDepth,
        vm::selectMember, vm::refresh, onDismiss)
    else Text(stringResource(R.string.conversation_map_loading), Modifier.padding(Spacing.lg))
}
