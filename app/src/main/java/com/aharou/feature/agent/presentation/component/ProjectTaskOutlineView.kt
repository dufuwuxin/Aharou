package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.task.ProjectTaskMapLayout
import com.aharou.feature.agent.domain.task.ProjectTaskOutline
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Activity

/** Lines shown per group before the rest fold into "N more". */
internal const val OUTLINE_FOLD = 5
// Minimum row heights (logical dp). The rows below use exactly these minimums, so at default and 1.3× font
// scale the composed height equals [estimateOutlineHeight]; larger fonts or unfolded full text are corrected by
// the map's onSizeChanged measurement.
private const val LINE_ROW = 32
private const val LABEL_ROW = 24
private const val ACTION_ROW = 40
private const val LINE_DOT = 6

/**
 * Unfolded node content, one dot per line (folded/unfolded), grouped by [ProjectTaskOutline.Kind].
 * Line keys are indices into [lines], stable for one outline snapshot.
 */
@Composable
internal fun ProjectTaskOutlineView(lines: List<ProjectTaskOutline.Line>, openGroups: Set<ProjectTaskOutline.Kind>,
    fullLines: Set<Int>, hitLines: Set<Int>, onToggleGroup: (ProjectTaskOutline.Kind) -> Unit, onToggleLine: (Int) -> Unit,
    onJump: (String) -> Unit, onOpenProcess: () -> Unit, modifier: Modifier = Modifier) {
    val weak = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.padding(start = Spacing.xxl, end = Spacing.sm, bottom = Spacing.sm)) {
        if (lines.isEmpty()) Box(Modifier.heightIn(min = LINE_ROW.dp), contentAlignment = Alignment.CenterStart) {
            Text(stringResource(R.string.project_outline_empty), style = MaterialTheme.typography.bodySmall, color = weak)
        }
        lines.withIndex().groupBy { it.value.kind }.forEach { (kind, group) ->
            Box(Modifier.heightIn(min = LABEL_ROW.dp), contentAlignment = Alignment.BottomStart) {
                Text(stringResource(outlineKindLabel(kind)), style = MaterialTheme.typography.labelSmall, color = weak)
            }
            val open = kind in openGroups
            (if (open) group else group.take(OUTLINE_FOLD)).forEach { (index, line) ->
                val hit = index in hitLines
                val full = index in fullLines
                val target = line.taskId
                Row(Modifier.fillMaxWidth().heightIn(min = LINE_ROW.dp)
                    .clickable { if (target != null) onJump(target) else onToggleLine(index) }
                    .testTag("outline_line_$index"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(LINE_DOT.dp).background(if (hit) accent else weak, CircleShape))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(line.text, maxLines = if (full) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hit) accent else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (target != null) Icon(FeatherIcons.ChevronRight, null, tint = weak, modifier = Modifier.size(Spacing.md))
                }
            }
            if (group.size > OUTLINE_FOLD) Box(Modifier.fillMaxWidth().heightIn(min = LINE_ROW.dp).clickable { onToggleGroup(kind) }
                .padding(start = Spacing.md), contentAlignment = Alignment.CenterStart) {
                Text(if (open) stringResource(R.string.project_outline_fold)
                    else stringResource(R.string.project_outline_more, group.size - OUTLINE_FOLD),
                    style = MaterialTheme.typography.labelSmall, color = weak)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = ACTION_ROW.dp).clickable(onClick = onOpenProcess).testTag("outline_process"),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(FeatherIcons.Activity, null, tint = weak, modifier = Modifier.size(Spacing.md))
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.project_outline_process), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Height of the unfolded node (header + outline) at default font scale; must mirror the rows above. */
internal fun estimateOutlineHeight(lines: List<ProjectTaskOutline.Line>, openGroups: Set<ProjectTaskOutline.Kind>): Float {
    var h = ProjectTaskMapLayout.NODE_HEIGHT + ACTION_ROW + Spacing.sm.value
    if (lines.isEmpty()) h += LINE_ROW
    lines.groupingBy { it.kind }.eachCount().forEach { (kind, count) ->
        h += LABEL_ROW + LINE_ROW * (if (kind in openGroups) count else minOf(count, OUTLINE_FOLD))
        if (count > OUTLINE_FOLD) h += LINE_ROW
    }
    return h
}

internal fun outlineKindLabel(kind: ProjectTaskOutline.Kind): Int = when (kind) {
    ProjectTaskOutline.Kind.REQUIREMENT -> R.string.project_outline_requirement
    ProjectTaskOutline.Kind.SUPPLEMENT -> R.string.project_outline_supplement
    ProjectTaskOutline.Kind.CONSTRAINT -> R.string.project_outline_constraint
    ProjectTaskOutline.Kind.ACCEPTANCE -> R.string.project_outline_acceptance
    ProjectTaskOutline.Kind.BACKGROUND -> R.string.project_outline_background
    ProjectTaskOutline.Kind.OWNER -> R.string.project_outline_owner
    ProjectTaskOutline.Kind.MEMBER -> R.string.project_outline_member
    ProjectTaskOutline.Kind.DEPENDENCY -> R.string.project_outline_dependency
    ProjectTaskOutline.Kind.RESULT -> R.string.project_outline_result
}
