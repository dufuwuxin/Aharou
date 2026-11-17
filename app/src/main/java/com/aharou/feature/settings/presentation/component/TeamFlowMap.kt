package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
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
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current.density
    val executeColor = MaterialTheme.colorScheme.primary
    val reviewColor = MaterialTheme.colorScheme.tertiary
    val rejectColor = MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(if (routes.isEmpty()) R.string.team_flow_legacy else R.string.team_flow_routed), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.team_relationship_hint), style = MaterialTheme.typography.labelSmall)
        Text(stringResource(R.string.team_flow_gesture_hint), style = MaterialTheme.typography.labelSmall)
        BoxWithConstraints(Modifier.fillMaxWidth().height(360.dp).clipToBounds()) {
            val viewWidth = maxWidth.value
            val viewHeight = maxHeight.value
            fun bounded(value: Offset, scale: Float) = Offset(
                value.x.coerceIn(minOf(0f, viewWidth - width * scale), 0f),
                value.y.coerceIn(minOf(0f, viewHeight - height * scale), 0f))
            LaunchedEffect(width, height, viewWidth, viewHeight) { pan = bounded(pan, zoom) }
            // Extent is virtual: allocate only a viewport canvas and visible fixed-size node layers.
            Box(Modifier.fillMaxSize().testTag("team_flow_viewport")
                .semantics {
                    stateDescription = zoom.toString()
                    horizontalScrollAxisRange = ScrollAxisRange({ -pan.x * density }, { maxOf(0f, width * zoom - viewWidth) * density })
                    verticalScrollAxisRange = ScrollAxisRange({ -pan.y * density }, { maxOf(0f, height * zoom - viewHeight) * density })
                    scrollBy { x, y -> pan = bounded(pan - Offset(x, y) / density, zoom); true }
                }
                .pointerInput(viewWidth, viewHeight, width, height) {
                    detectTransformGestures { centroid, movement, factor, _ ->
                        val next = (zoom * factor).coerceIn(0.5f, 2f)
                        val center = centroid / density
                        pan = bounded(center - (center - pan) * (next / zoom) + movement / density, next)
                        zoom = next
                    }
                }) {
                Canvas(Modifier.fillMaxSize()) {
                    withTransform({ translate(pan.x * density, pan.y * density); scale(zoom, zoom, Offset.Zero) }) {
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
                }
                rows.forEachIndexed { stageIndex, (stage, tasks) -> tasks.forEachIndexed member@{ memberIndex, task ->
                    val node = TeamFlowNode(stage.id, task.id)
                    val x = (memberIndex * 240 + 36) * zoom + pan.x
                    val y = (stageIndex * 160 + 120) * zoom + pan.y
                    if (x + 216 * zoom < 0 || y + 88 * zoom < 0 || x > viewWidth || y > viewHeight) return@member
                    Surface(color = if (node in selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.graphicsLayer {
                            translationX = x * density; translationY = y * density
                            scaleX = zoom; scaleY = zoom; transformOrigin = TransformOrigin(0f, 0f)
                        }.requiredSize(216.dp, 88.dp).testTag("team_graph_node_${teamNodeReference(node)}").clickable { onNodeClick(node) }) {
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
