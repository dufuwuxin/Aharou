package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.subagent.*

/** Administrator organization edges are distinct from typed, runtime-consumed workflow arrows. */
@Composable
internal fun TeamFlowMap(stages: List<AgentTeamStageForm>, routes: List<TeamRoute>, selected: Set<TeamFlowNode>, onNodeClick: (TeamFlowNode) -> Unit) {
    val rows = stages.map { stage -> stage to stage.tasks.filter { it.prompt.isNotBlank() } }
    val count = maxOf(1, rows.maxOfOrNull { it.second.size } ?: 1)
    val width = count * 240 + 96
    val height = rows.size * 160 + 224
    val positions = buildMap { rows.forEachIndexed { stageIndex, (stage, tasks) -> tasks.forEachIndexed { memberIndex, task ->
        put(TeamFlowNode(stage.id, task.id), memberIndex to stageIndex)
    } } }
    val executeColor = MaterialTheme.colorScheme.primary
    val reviewColor = MaterialTheme.colorScheme.tertiary
    val rejectColor = MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(if (routes.isEmpty()) R.string.team_flow_legacy else R.string.team_flow_routed), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.team_relationship_hint), style = MaterialTheme.typography.labelSmall)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(width.dp).height(height.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    rows.forEach { (stage, tasks) -> tasks.forEach { task ->
                        val source = positions[TeamFlowNode(stage.id, task.id)] ?: return@forEach
                        task.interfaces.forEach { edge ->
                            val targetNode = positions.keys.firstOrNull { node ->
                                val targetStage = stages.first { it.id == node.stageId }
                                val targetTask = targetStage.tasks.first { it.id == node.taskId }
                                edge.with == teamNodeReference(node) || edge.with == targetTask.agent || edge.with == targetStage.title
                            } ?: return@forEach
                            val target = positions.getValue(targetNode)
                            val x1 = (source.first * 240 + 144).dp.toPx()
                            val y1 = (source.second * 160 + 164).dp.toPx()
                            val x2 = (target.first * 240 + 144).dp.toPx()
                            val y2 = (target.second * 160 + 164).dp.toPx()
                            val bend = ((x1 + x2) / 2) + 48.dp.toPx()
                            val path = Path().apply { moveTo(x1, y1); cubicTo(bend, y1 - 56.dp.toPx(), bend, y2 - 56.dp.toPx(), x2, y2) }
                            drawPath(path, reviewColor, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                        }
                    } }
                    routes.forEachIndexed { routeIndex, edge ->
                        val from = positions[edge.from] ?: return@forEachIndexed
                        val to = positions[edge.to] ?: return@forEachIndexed
                        val x1 = (from.first * 240 + 144).dp.toPx(); val y1 = (from.second * 160 + 208).dp.toPx()
                        val x2 = (to.first * 240 + 144).dp.toPx(); val y2 = (to.second * 160 + 120).dp.toPx()
                        val color = when (edge.action) { TeamRouteAction.EXECUTE -> executeColor; TeamRouteAction.REVIEW -> reviewColor; TeamRouteAction.REJECT -> rejectColor }
                        val path = Path().apply {
                            moveTo(x1, y1)
                            if (edge.action == TeamRouteAction.REJECT || from.second == to.second) {
                                val rail = (width - 16 - routeIndex % 4 * 8).dp.toPx()
                                lineTo(rail, y1 + 8.dp.toPx()); lineTo(rail, y2 - 8.dp.toPx()); lineTo(x2, y2 - 8.dp.toPx())
                            } else { val middle = (y1 + y2) / 2; lineTo(x1, middle); lineTo(x2, middle) }
                            lineTo(x2, y2)
                        }
                        drawPath(path, color, style = Stroke(2.dp.toPx(), pathEffect = if (edge.action == TeamRouteAction.REJECT) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx())) else null))
                        drawLine(color, Offset(x2 - 4.dp.toPx(), y2 - 8.dp.toPx()), Offset(x2, y2), 2.dp.toPx())
                        drawLine(color, Offset(x2 + 4.dp.toPx(), y2 - 8.dp.toPx()), Offset(x2, y2), 2.dp.toPx())
                    }
                }
                rows.forEachIndexed { stageIndex, (stage, tasks) -> tasks.forEachIndexed { memberIndex, task ->
                    val node = TeamFlowNode(stage.id, task.id)
                    Surface(color = if (node in selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.offset(x = (memberIndex * 240 + 36).dp, y = (stageIndex * 160 + 120).dp)
                            .width(216.dp).height(88.dp).testTag("team_graph_node_${teamNodeReference(node)}").clickable { onNodeClick(node) }) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text(stage.title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            Text(task.agent.ifBlank { task.department.ifBlank { task.agentQuery } }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            val actions = routes.filter { it.to == node }.map { it.action }.distinct().map { teamRouteActionLabel(it) }
                            Text(if (task.interfaces.isNotEmpty()) stringResource(R.string.team_interface_count, task.interfaces.size)
                                else actions.joinToString(" / "), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TeamRouteAction.entries.forEach { Text(teamRouteActionLabel(it), color = when (it) {
                TeamRouteAction.EXECUTE -> executeColor; TeamRouteAction.REVIEW -> reviewColor; TeamRouteAction.REJECT -> rejectColor
            }, style = MaterialTheme.typography.labelMedium) }
        }
    }
}
