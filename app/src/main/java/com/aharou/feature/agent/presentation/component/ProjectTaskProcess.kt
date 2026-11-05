package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.task.ProjectTask

/** Newest user supplements shown read-only in the process header; the count line reports the total. */
private const val SUPPLEMENTS_SHOWN = 3

@Composable
internal fun ProjectTaskProcess(task: ProjectTask, tasks: List<ProjectTask>, onEdit: () -> Unit, onAccept: () -> Unit,
    conversation: @Composable (ProjectTask, String) -> Unit, modifier: Modifier = Modifier) {
    val records = (task.sessionIds + task.processSessionIds).distinct()
    var selectedSession by rememberSaveable(task.id) { mutableStateOf(records.firstOrNull()) }
    LaunchedEffect(records) { if (selectedSession !in records) selectedSession = records.firstOrNull() }
    Column(modifier.testTag("task_process_${task.id}"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(task.requirement, maxLines = 3, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.project_task_owner_value, task.owner.ifBlank { stringResource(R.string.project_flow_default_member) }),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text(stringResource(R.string.project_task_edit)) }
        }
        if (task.supplements.isNotEmpty()) {
            Text(stringResource(R.string.project_supplements_title, task.supplements.size), style = MaterialTheme.typography.labelMedium)
            task.supplements.takeLast(SUPPLEMENTS_SHOWN).forEach { line ->
                Text(stringResource(R.string.project_supplement_item, line), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (task.members.isNotEmpty()) Text(stringResource(R.string.project_task_collaboration_value, task.owner, task.members.joinToString("、")),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.project_flow_predecessors, task.dependencyIds.mapNotNull { id -> tasks.firstOrNull { it.id == id }?.title }
            .joinToString("、").ifBlank { stringResource(R.string.project_flow_no_predecessor) }), style = MaterialTheme.typography.labelSmall)
        if (task.executionReason.isNotBlank()) Text(if (task.executionReason == "TIMEOUT") stringResource(R.string.project_member_timeout)
            else projectTaskExecutionReasonLabel(task.executionReason), style = MaterialTheme.typography.labelSmall)
        if (records.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            records.forEachIndexed { index, id ->
                FilterChip(selected = selectedSession == id, onClick = { selectedSession = id },
                    label = {
                        val member = task.processMembers[id]
                        Text(if (member == null) stringResource(R.string.project_process_record, index + 1)
                            else stringResource(R.string.project_process_member_record,
                                member.role.ifBlank { stringResource(R.string.project_flow_default_member) }, member.ordinal, index + 1))
                    })
            }
        }
        if (task.status == com.aharou.feature.agent.domain.task.ProjectTaskStatus.REVIEW) TextButton(onClick = onAccept,
            enabled = task.acceptance.isNotBlank() && task.evidenceRefs.isNotEmpty() &&
                (task.processSessionIds.isEmpty() || com.aharou.feature.agent.domain.task.ProjectWorkflowPlan.delivered(task))) { Text(stringResource(R.string.project_task_accept)) }
        HorizontalDivider()
        val sessionId = selectedSession?.takeIf { it in records }
        if (sessionId == null) Text(stringResource(R.string.project_process_empty), style = MaterialTheme.typography.bodySmall)
        else {
            task.processMembers[sessionId]?.let { member ->
                Text(when (member.outcome) {
                    "TIMEOUT" -> stringResource(R.string.project_member_timeout)
                    "RUNNING" -> projectTaskStatusLabel(com.aharou.feature.agent.domain.task.ProjectTaskStatus.RUNNING)
                    else -> projectTaskExecutionReasonLabel(member.outcome)
                }, style = MaterialTheme.typography.labelSmall)
                if (member.reason.isNotBlank()) Text(member.reason, maxLines = 3, style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { conversation(task, sessionId) }
        }
    }
}
