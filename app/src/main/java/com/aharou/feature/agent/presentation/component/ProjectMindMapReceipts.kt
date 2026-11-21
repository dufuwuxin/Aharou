package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import com.aharou.feature.agent.domain.task.ProjectTaskMemberRun
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MapReceiptFilters(detail: MapTaskDetailState) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        MapReceiptFilter.entries.forEach { filter ->
            val label = when (filter) {
                MapReceiptFilter.ALL -> R.string.pmap_filter_all
                MapReceiptFilter.WEB -> R.string.pmap_filter_web
                MapReceiptFilter.MEMORY -> R.string.pmap_filter_memory
                MapReceiptFilter.FILE -> R.string.pmap_filter_file
                MapReceiptFilter.OTHER -> R.string.pmap_filter_other
                MapReceiptFilter.ERROR -> R.string.pmap_filter_error
                MapReceiptFilter.DENIED -> R.string.pmap_filter_denied
            }
            FilterChip(selected = detail.filter == filter, onClick = { detail.filter = filter },
                label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("pmap_filter_${filter.name}"))
        }
    }
}

@Composable
internal fun MapReceiptEntry(receipt: ToolCallReceiptEntity, member: ProjectTaskMemberRun?, expanded: Boolean,
    focusRequested: Boolean = false, onFocused: () -> Unit = {}, onToggle: () -> Unit) {
    val focus = remember(receipt.id) { FocusRequester() }
    LaunchedEffect(focusRequested) {
        if (focusRequested) { focus.requestFocus(); onFocused() }
    }
    val outcome = ProjectMindMapModels.outcome(receipt)
    val semantic = MaterialTheme.semanticColors
    val outcomeLabel = stringResource(when (outcome) {
        MapReceiptOutcome.SUCCESS -> R.string.pmap_receipt_success
        MapReceiptOutcome.PARTIAL -> R.string.pmap_receipt_partial
        MapReceiptOutcome.ERROR -> R.string.pmap_receipt_error
        MapReceiptOutcome.DENIED -> R.string.pmap_receipt_denied
        MapReceiptOutcome.UNKNOWN -> R.string.pmap_receipt_unknown
    })
    val kindLabel = stringResource(when (ProjectMindMapModels.kind(receipt)) {
        MapReceiptKind.WEB -> R.string.pmap_filter_web
        MapReceiptKind.MEMORY -> R.string.pmap_filter_memory
        MapReceiptKind.FILE -> R.string.pmap_filter_file
        MapReceiptKind.OTHER -> R.string.pmap_filter_other
    })
    val time = remember(receipt.createdAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(receipt.createdAt))
    }
    val expandedLabel = stringResource(if (expanded) R.string.pmap_expanded else R.string.pmap_collapsed)
    Surface(shape = RoundedCornerShape(Radius.mdLarge), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Surface(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .focusRequester(focus).semantics { stateDescription = expandedLabel }.testTag("pmap_receipt_${receipt.id}")) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.pmap_receipt_time, time), style = MaterialTheme.typography.labelMedium)
                    Text("$kindLabel · ${receipt.toolName}", style = MaterialTheme.typography.titleSmall)
                    val background = when (outcome) {
                        MapReceiptOutcome.SUCCESS -> semantic.successContainer
                        MapReceiptOutcome.ERROR -> MaterialTheme.colorScheme.errorContainer
                        MapReceiptOutcome.DENIED, MapReceiptOutcome.PARTIAL -> semantic.warningContainer
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    }
                    Surface(color = background, contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(Radius.sm)) {
                        Text(outcomeLabel, Modifier.padding(Spacing.sm), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(expandedLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (expanded) Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                MapField(R.string.pmap_tool, receipt.toolName)
                MapField(R.string.pmap_executor, member?.role?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.pmap_executor_unknown))
                member?.let {
                    Text(stringResource(R.string.pmap_run_role_info, it.requirementsVersion, it.outcome, it.reason))
                }
                MapField(R.string.pmap_args, receipt.argsPreview.ifBlank { stringResource(R.string.pmap_no_args) })
                MapField(R.string.pmap_result, receipt.resultPreview.ifBlank { stringResource(R.string.pmap_no_result) })
                receipt.errorCode?.takeIf { it.isNotBlank() }?.let { MapField(R.string.pmap_error_code, it) }
                if (outcome == MapReceiptOutcome.UNKNOWN) MapField(R.string.pmap_raw_status, receipt.status.ifBlank { stringResource(R.string.pmap_not_recorded) })
                if (receipt.durationMs > 0) MapField(R.string.pmap_duration, stringResource(R.string.pmap_duration_value, receipt.durationMs))
                receipt.outputPath?.takeIf { it.isNotBlank() }?.let { MapField(R.string.pmap_archive_ref, it) }
                if (ProjectMindMapModels.kind(receipt) == MapReceiptKind.FILE) {
                    val hunks = remember(receipt.resultPreview, receipt.status, receipt.category) { ProjectMindMapModels.diff(receipt) }
                    if (hunks.isEmpty()) Text(stringResource(R.string.pmap_no_diff))
                    else {
                        Text(stringResource(R.string.pmap_recorded_diff), style = MaterialTheme.typography.titleSmall)
                        hunks.forEach { diff -> SelectionContainer {
                            Column(Modifier.fillMaxWidth().testTag("pmap_diff_${receipt.id}")) {
                                diff.lineSequence().forEach { line ->
                                    val tint = when {
                                        line.startsWith("+") -> semantic.successContainer
                                        line.startsWith("-") -> MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.surfaceContainer
                                    }
                                    Surface(color = tint, modifier = Modifier.fillMaxWidth()) {
                                        Text(line, Modifier.padding(horizontal = 4.dp), fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        } }
                    }
                }
            }
        }
    }
}
