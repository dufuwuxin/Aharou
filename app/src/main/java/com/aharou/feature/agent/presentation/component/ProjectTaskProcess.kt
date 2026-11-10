package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
    conversation: @Composable (ProjectTask, String) -> Unit, modifier: Modifier = Modifier, readOnly: Boolean = false,
    fillConversation: Boolean = true, acceptEnabled: Boolean = true,
    onReadFull: ((String?) -> Unit)? = null, initialSession: String? = null, onRecordSelected: ((String?) -> Unit)? = null) {
    if (task.isRequirementBranch) {
        Column(modifier.testTag("requirement_branch_${task.id}"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.project_requirement_branch), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.project_branch_discussion_hint), style = MaterialTheme.typography.bodySmall)
            if (task.requirement.isNotBlank()) Text(task.requirement, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            var selected by rememberSaveable(task.id, initialSession) { mutableStateOf(initialSession) }
            val sessionId = selected?.takeIf { it in task.sessionIds } ?: task.sessionIds.firstOrNull()
            if (task.sessionIds.size > 1) Row(Modifier.horizontalScroll(rememberScrollState())) {
                task.sessionIds.forEachIndexed { index, record ->
                    FilterChip(selected = record == sessionId, onClick = { selected = record },
                        label = { Text(stringResource(R.string.project_main_record, index + 1)) })
                }
            }
            if (sessionId == null) Text(stringResource(R.string.project_process_empty))
            else Box(if (fillConversation) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth()) {
                key(task.id, sessionId) { conversation(task, sessionId) }
            }
        }
        return
    }
    val records = projectProcessRecords(task)
    val recordState = key(task.id) { rememberSaveableStateHolder() }
    var selectedSession by rememberSaveable(task.id, initialSession) { mutableStateOf(projectRecordSelection(records, initialSession)) }
    LaunchedEffect(records) { if (selectedSession !in records) selectedSession = projectRecordSelection(records, null) }
    LaunchedEffect(task.id, selectedSession) { onRecordSelected?.invoke(selectedSession) }
    Column(modifier.testTag("task_process_${task.id}"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(task.requirement, maxLines = 3, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.project_task_owner_value, task.owner.ifBlank { stringResource(R.string.project_flow_default_member) }),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (!readOnly) TextButton(onClick = onEdit) { Text(stringResource(R.string.project_task_edit)) }
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
        onReadFull?.let { open -> TextButton(onClick = { open(selectedSession) }) { Text(stringResource(R.string.project_process_read_full)) } }
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
            enabled = acceptEnabled && task.acceptance.isNotBlank() && task.evidenceRefs.isNotEmpty() &&
                (task.processSessionIds.isEmpty() || com.aharou.feature.agent.domain.task.ProjectWorkflowPlan.delivered(task))) { Text(stringResource(R.string.project_task_accept)) }
        HorizontalDivider()
        val sessionId = selectedSession?.takeIf { it in records }
        if (sessionId == null) Text(stringResource(R.string.project_process_empty), style = MaterialTheme.typography.bodySmall)
        else {
            task.processMembers[sessionId]?.let { member ->
                Text(when (member.outcome) {
                    "TIMEOUT" -> stringResource(R.string.project_member_timeout)
                    "RUNNING" -> stringResource(R.string.project_member_status_unconfirmed)
                    else -> projectTaskExecutionReasonLabel(member.outcome)
                }, style = MaterialTheme.typography.labelSmall)
                if (member.reason.isNotBlank()) Text(member.reason, maxLines = 3, style = MaterialTheme.typography.bodySmall)
            }
            Box(if (fillConversation) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth()) {
                recordState.SaveableStateProvider(sessionId) {
                    key(task.id, sessionId) { conversation(task, sessionId) }
                }
            }
        }
    }
}
