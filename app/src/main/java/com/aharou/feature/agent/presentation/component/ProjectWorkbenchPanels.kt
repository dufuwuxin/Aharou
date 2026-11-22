package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.data.local.dao.ProjectCallUsage
import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import com.aharou.feature.agent.domain.task.ProjectTask
import kotlinx.coroutines.delay

private val workbenchTabs = listOf(
    "tasks" to R.string.project_workbench_tasks,
    "collaboration" to R.string.project_workbench_collaboration,
    "execution" to R.string.project_workbench_execution,
    "changes" to R.string.project_workbench_changes,
    "usage" to R.string.project_workbench_usage,
)

@Composable
internal fun ProjectWorkbenchNavigation(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        workbenchTabs.forEach { (key, label) ->
            val active = selected == key
            Surface(
                onClick = { onSelect(key) },
                shape = MaterialTheme.shapes.medium,
                color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Column(Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.Center) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun ProjectWorkbenchPanel(
    tab: String,
    tasks: List<ProjectTask>,
    receipts: List<ToolCallReceiptEntity>,
    callUsage: ProjectCallUsage,
    onOpenTask: (ProjectTask) -> Unit,
) {
    when (tab) {
        "collaboration" -> CollaborationPanel(tasks, onOpenTask)
        "execution" -> ExecutionPanel(tasks, onOpenTask)
        "changes" -> ChangesPanel(receipts)
        "usage" -> UsagePanel(callUsage, tasks)
        else -> Text(stringResource(R.string.project_workbench_select_task), modifier = Modifier.padding(Spacing.lg))
    }
}

@Composable
private fun CollaborationPanel(tasks: List<ProjectTask>, onOpenTask: (ProjectTask) -> Unit) {
    val assignments = tasks.flatMap { task ->
        (listOf(task.owner) + task.members + task.processMembers.values.map { it.role })
            .filter { it.isNotBlank() }.distinct().map { it to task }
    }.distinctBy { (role, task) -> role to task.id }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.project_workbench_assignment_authority),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = Spacing.sm))
        if (assignments.isEmpty()) {
            EmptyPanel(R.string.project_workbench_no_assignments)
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            items(assignments, key = { (role, task) -> "$role:${task.id}" }) { (role, task) ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenTask(task) }) {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(role, style = MaterialTheme.typography.titleSmall)
                        Text(task.title, style = MaterialTheme.typography.bodySmall)
                        val run = task.processMembers.values.filter { it.role == role }.maxByOrNull { it.ordinal }
                        Text(stringResource(R.string.project_workbench_assignment_status,
                            run?.let { memberRunLabel(it.outcome) }
                                ?: stringResource(R.string.project_workbench_assignment_unconfirmed)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionPanel(tasks: List<ProjectTask>, onOpenTask: (ProjectTask) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tasks.any { it.runStartedAtEpochMs != null }) {
        if (tasks.none { it.runStartedAtEpochMs != null }) return@LaunchedEffect
        while (true) { delay(1_000); now = System.currentTimeMillis() }
    }
    if (tasks.isEmpty()) return EmptyPanel(R.string.project_workbench_no_execution)
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        items(tasks, key = { it.id }) { task ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clickable { onOpenTask(task) }) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(task.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(projectTaskStatusLabel(task.status), style = MaterialTheme.typography.labelMedium,
                            color = if (task.status == com.aharou.feature.agent.domain.task.ProjectTaskStatus.BLOCKED)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    Text(if (task.executionReason.isNotBlank()) projectTaskExecutionReasonLabel(task.executionReason)
                        else stringResource(R.string.project_workbench_execution_waiting),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val duration = task.lastRunDurationMs
                    val elapsed = task.runStartedAtEpochMs?.let { (now - it).coerceAtLeast(0) }
                    Text(elapsed?.let { stringResource(R.string.project_workbench_running_duration, formatTaskDuration(it)) }
                        ?: duration?.let { stringResource(R.string.project_workbench_duration_ms, formatTaskDuration(it)) }
                        ?: stringResource(R.string.project_workbench_duration_unknown),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ChangesPanel(receipts: List<ToolCallReceiptEntity>) {
    val changes = receipts.filter { receipt ->
        receipt.toolName.equals("writeFile", ignoreCase = true) ||
            receipt.toolName.equals("editFile", ignoreCase = true) ||
            receipt.toolName.equals("applyPatch", ignoreCase = true)
    }
    if (changes.isEmpty()) return EmptyPanel(R.string.project_workbench_no_change_receipts)
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        items(changes, key = { it.id }) { item ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(item.toolName, style = MaterialTheme.typography.titleSmall)
                    if (item.argsPreview.isNotBlank()) Text(item.argsPreview, style = MaterialTheme.typography.bodySmall)
                    Text(item.resultPreview.ifBlank { item.errorCode.orEmpty() }, style = MaterialTheme.typography.bodySmall,
                        color = if (item.status == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.project_workbench_receipt_time,
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                            .format(java.util.Date(item.createdAt)), formatTaskDuration(item.durationMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun UsagePanel(usage: ProjectCallUsage, tasks: List<ProjectTask>) {
    val hitRate = if (usage.cacheReportedInputTokens > 0L)
        (usage.cachedTokens * 100 / usage.cacheReportedInputTokens).coerceAtMost(100) else null
    val totalDuration = usage.durationMillis
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        MetricRow(R.string.project_workbench_input_tokens, usage.inputTokens, usage.calls > 0)
        MetricRow(R.string.project_workbench_output_tokens, usage.outputTokens, usage.calls > 0)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(Spacing.md), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.project_workbench_cache_hit_rate), style = MaterialTheme.typography.bodyMedium)
                Text(hitRate?.let { "$it%" } ?: stringResource(R.string.project_workbench_usage_unknown),
                    style = MaterialTheme.typography.titleSmall)
            }
        }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(Spacing.md), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.project_workbench_model_duration), style = MaterialTheme.typography.bodyMedium)
                Text(if (usage.durationCalls > 0) stringResource(R.string.project_workbench_duration_ms, formatTaskDuration(totalDuration))
                    else stringResource(R.string.project_workbench_usage_unknown), style = MaterialTheme.typography.titleSmall)
            }
        }
        Text(stringResource(R.string.project_workbench_cache_method, usage.cacheReportedCalls, usage.calls),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.project_workbench_duration_method, usage.durationCalls, usage.calls),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tasks.isEmpty() && usage.calls == 0L) EmptyPanel(R.string.project_workbench_usage_empty)
    }
}

@Composable
private fun MetricRow(label: Int, value: Long, available: Boolean) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(Spacing.md), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            Text(if (available) value.toString() else stringResource(R.string.project_workbench_usage_unknown),
                style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun memberRunLabel(outcome: String): String = when (outcome) {
    "SUCCEEDED" -> stringResource(R.string.project_workbench_member_succeeded)
    "FAILED" -> stringResource(R.string.project_workbench_member_failed)
    "STOPPED" -> stringResource(R.string.project_workbench_member_stopped)
    "TIMEOUT" -> stringResource(R.string.project_member_timeout)
    "RUNNING" -> stringResource(R.string.project_task_state_running)
    else -> stringResource(R.string.project_workbench_assignment_unconfirmed)
}

@Composable
private fun EmptyPanel(message: Int) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(stringResource(message), modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
