package com.aharou.feature.agent.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import com.aharou.feature.agent.domain.task.*
import com.aharou.feature.agent.presentation.ProjectTaskViewModel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

internal data class MapReceiptRead(val receipts: List<ToolCallReceiptEntity> = emptyList(),
    val loading: Boolean = true, val failed: Boolean = false)

@Stable
internal class MapTaskDetailState {
    var tab by mutableStateOf("progress")
    var filter by mutableStateOf(MapReceiptFilter.ALL)
    var expanded by mutableStateOf(arrayListOf<Long>())
    var jumpId by mutableStateOf<Long?>(null)
    var focusReceiptId by mutableStateOf<Long?>(null)
    var newRecords by mutableStateOf(false)
    var read by mutableStateOf(MapReceiptRead())
    val receipts get() = read.receipts
}

@Composable
internal fun rememberMapTaskDetail(task: ProjectTask, viewModel: ProjectTaskViewModel): MapTaskDetailState {
    val sessions = remember(task.sessionIds, task.processSessionIds) { ProjectMindMapModels.sessions(task) }
    val detail = rememberSaveable(task.workspace, task.projectId, task.id, saver = mapSaver<MapTaskDetailState>(
        save = { mapOf("tab" to it.tab, "filter" to it.filter.name, "expanded" to it.expanded.toLongArray()) },
        restore = { saved -> MapTaskDetailState().apply {
            tab = saved["tab"] as String
            filter = MapReceiptFilter.valueOf(saved["filter"] as String)
            expanded = ArrayList((saved["expanded"] as LongArray).toList())
        } }
    )) { MapTaskDetailState() }
    val source = remember(task.workspace, task.projectId, task.id, sessions) {
        flow {
            var last = emptyList<ToolCallReceiptEntity>()
            emit(MapReceiptRead())
            try {
                viewModel.projectReceipts(task.workspace, sessions).collect { rows ->
                    last = ProjectMindMapModels.ordered(rows.filter { it.sessionId in sessions })
                    emit(MapReceiptRead(last, loading = false))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { emit(MapReceiptRead(last, loading = false, failed = true)) }
        }
    }
    // key resets the collected value immediately when task/session identity changes (no stale A -> B frame).
    val read = key(task.workspace, task.projectId, task.id, sessions) {
        source.collectAsStateWithLifecycle(initialValue = MapReceiptRead()).value
    }
    var previousIds by remember(task.id, sessions) { mutableStateOf<Set<Long>?>(null) }
    detail.read = read
    LaunchedEffect(read.receipts, read.loading) {
        if (!read.loading) {
            val ids = read.receipts.map { it.id }.toSet()
            if (previousIds != null && (ids - previousIds.orEmpty()).isNotEmpty()) detail.newRecords = true
            previousIds = ids
        }
        if (!read.loading) detail.expanded = ArrayList(detail.expanded.filter { id -> read.receipts.any { it.id == id } })
    }
    return detail
}

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.projectTaskDetailItems(task: ProjectTask, tasks: List<ProjectTask>, active: Set<String>,
    detail: MapTaskDetailState, navigate: (String) -> Unit) {
    item("task_tabs") {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(selected = detail.tab == "progress", onClick = { detail.tab = "progress" },
                label = { Text(stringResource(R.string.pmap_progress)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("pmap_tab_progress"))
            FilterChip(selected = detail.tab == "log", onClick = { detail.tab = "log" },
                label = { Text(stringResource(R.string.pmap_operations, detail.receipts.size)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("pmap_tab_log"))
        }
    }
    if (detail.tab == "progress") item("task_progress") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            MapField(R.string.pmap_requirement, task.requirement.ifBlank { stringResource(R.string.pmap_not_provided) })
            if (task.brief.isNotBlank()) MapField(R.string.pmap_brief, task.brief)
            if (task.background.isNotBlank()) MapField(R.string.pmap_background, task.background)
            if (task.constraints.isNotEmpty()) MapField(R.string.pmap_constraints, task.constraints.joinToString("\n"))
            if (task.supplements.isNotEmpty()) MapField(R.string.pmap_supplements, task.supplements.joinToString("\n"))
            Text(stringResource(R.string.pmap_roles), style = MaterialTheme.typography.titleSmall)
            val roles = ProjectMindMapModels.roles(listOf(task))
            if (roles.isEmpty()) Text(stringResource(R.string.pmap_unassigned))
            roles.forEach { role -> MapLink(role + " · " + MapRoleRelation(task, role)) { navigate("role:$role") } }
            Text(stringResource(R.string.pmap_dependencies), style = MaterialTheme.typography.titleSmall)
            if (task.dependencyIds.isEmpty()) Text(stringResource(R.string.pmap_no_dependencies))
            task.dependencyIds.forEach { id ->
                val dependency = tasks.firstOrNull { it.id == id }
                if (dependency == null) Text(stringResource(R.string.pmap_dependency_missing, id))
                else {
                    MapLink(dependency.title + " · " + stringResource(mapTaskStatusResource(dependency))) { navigate("task:$id") }
                    if (!ProjectWorkflowPlan.delivered(dependency)) Text(stringResource(R.string.pmap_dependency_pending))
                }
            }
            if (ProjectMindMapModels.cyclic(task, tasks)) Text(stringResource(R.string.pmap_dependency_cycle))
            MapField(R.string.pmap_reason, task.executionReason.ifBlank { stringResource(R.string.pmap_no_reason) })
            MapField(R.string.pmap_evidence, task.evidenceRefs.joinToString("\n").ifBlank { stringResource(R.string.pmap_no_evidence) })
            MapField(R.string.pmap_acceptance, task.acceptance.ifBlank { stringResource(R.string.pmap_no_acceptance) })
            MapField(R.string.pmap_versions, stringResource(R.string.pmap_version_values, task.requirementsVersion,
                task.acceptedRequirementsVersion?.toString() ?: stringResource(R.string.pmap_not_recorded),
                task.processRequirementsVersion?.toString() ?: stringResource(R.string.pmap_not_recorded)))
            if (!task.isRequirementBranch && task.status == ProjectTaskStatus.DONE && !ProjectMindMapModels.accepted(task))
                Text(stringResource(R.string.pmap_acceptance_pending))
            if (task.processRequirementsVersion != null && task.processRequirementsVersion != task.requirementsVersion)
                Text(stringResource(R.string.pmap_historical_execution))
            detail.receipts.lastOrNull()?.let { receipt ->
                OutlinedButton(onClick = {
                    detail.tab = "log"; detail.filter = MapReceiptFilter.ALL
                    detail.expanded = ArrayList((detail.expanded + receipt.id).distinct())
                    detail.jumpId = receipt.id; detail.focusReceiptId = receipt.id
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pmap_recent")) {
                    Text(stringResource(R.string.pmap_recent, receipt.toolName))
                }
            }
            if (detail.read.failed) Text(stringResource(R.string.pmap_receipt_failed))
        }
    } else {
        item("receipt_notice") { Text(stringResource(R.string.pmap_summary_notice), style = MaterialTheme.typography.bodyMedium) }
        item("receipt_filters") { MapReceiptFilters(detail) }
        item("receipt_count") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (detail.read.loading) {
                    CircularProgressIndicator(); Text(stringResource(R.string.pmap_receipt_loading))
                } else {
                    if (detail.read.failed) Text(stringResource(if (detail.receipts.isEmpty()) R.string.pmap_receipt_failed else R.string.pmap_receipt_stale))
                    if (detail.newRecords) TextButton(onClick = { detail.newRecords = false }) {
                        Text(stringResource(R.string.pmap_new_records))
                    }
                    Text(stringResource(R.string.pmap_loaded, detail.receipts.size))
                    if (detail.receipts.size >= 300) Text(stringResource(R.string.pmap_limit))
                    Text(stringResource(R.string.pmap_attribution_notice))
                    Text(stringResource(R.string.pmap_batches_unknown))
                    if (detail.receipts.isEmpty() && !detail.read.failed) Text(stringResource(
                        if (ProjectMindMapModels.sessions(task).isEmpty()) R.string.pmap_no_sessions else R.string.pmap_no_receipts))
                }
            }
        }
        val visible = detail.receipts.filter { ProjectMindMapModels.matches(it, detail.filter) }
        if (visible.isEmpty() && detail.receipts.isNotEmpty()) item("no_matches") { Text(stringResource(R.string.pmap_no_matches)) }
        visible.forEach { receipt -> item("receipt_${receipt.id}") {
            MapReceiptEntry(receipt, task.processMembers[receipt.sessionId], receipt.id in detail.expanded,
                focusRequested = detail.focusReceiptId == receipt.id, onFocused = { detail.focusReceiptId = null }) {
                detail.expanded = ArrayList(if (receipt.id in detail.expanded) detail.expanded - receipt.id else detail.expanded + receipt.id)
            }
        } }
    }
}

@StringRes
internal fun mapTaskStatusResource(task: ProjectTask): Int = if (task.isRequirementBranch) R.string.pmap_requirement_group else when (task.status) {
    ProjectTaskStatus.DRAFT -> R.string.pmap_draft
    ProjectTaskStatus.READY -> R.string.pmap_ready
    ProjectTaskStatus.RUNNING -> R.string.pmap_running
    ProjectTaskStatus.REVIEW -> R.string.pmap_review
    ProjectTaskStatus.DONE -> R.string.pmap_done
    ProjectTaskStatus.BLOCKED -> R.string.pmap_blocked
}

@Composable
internal fun MapTaskStatus(task: ProjectTask, active: Boolean) {
    val semantic = MaterialTheme.semanticColors
    val background = when {
        task.isRequirementBranch -> MaterialTheme.colorScheme.surfaceContainer
        task.status == ProjectTaskStatus.DONE -> semantic.successContainer
        task.status == ProjectTaskStatus.BLOCKED || task.status == ProjectTaskStatus.REVIEW -> semantic.warningContainer
        task.status == ProjectTaskStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Surface(color = background, contentColor = MaterialTheme.colorScheme.onSurface) {
        Text(stringResource(mapTaskStatusResource(task)), Modifier.padding(Spacing.sm), style = MaterialTheme.typography.labelLarge)
    }
    if (!task.isRequirementBranch && task.status == ProjectTaskStatus.RUNNING)
        Text(stringResource(if (active) R.string.pmap_active else R.string.pmap_execution_unconfirmed))
}

@Composable
internal fun MapField(@StringRes label: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun MapLink(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm)) {
        Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
    }
}
