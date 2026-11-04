package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskMapLayout

/** Bounded physical viewport; only logical coordinates grow with task depth. */
@Composable
fun ProjectTaskMap(projectId: String, projectTitle: String, tasks: List<ProjectTask>, selectedId: String?,
    onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val layout = remember(projectId, tasks) { ProjectTaskMapLayout.build(projectId, tasks) }
    val byId = remember(tasks) { tasks.associateBy { it.id } }
    val positions = remember(layout) { layout.nodes.associateBy { it.id } }
    val density = LocalDensity.current.density
    var zoom by remember(projectId) { mutableFloatStateOf(0.75f) }
    var panX by remember(projectId) { mutableFloatStateOf(0f) }
    var panY by remember(projectId) { mutableFloatStateOf(0f) }
    val edgeColor = MaterialTheme.colorScheme.outline
    val dependencyColor = MaterialTheme.colorScheme.tertiary
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TextButton(onClick = { zoom = (zoom * 1.3f).coerceAtMost(2f) }, modifier = Modifier.heightIn(min = 48.dp).testTag("map_zoom_in")) { Text(stringResource(R.string.project_map_zoom_in)) }
            TextButton(onClick = { zoom = (zoom / 1.3f).coerceAtLeast(ProjectTaskMapLayout.MIN_ZOOM) }, modifier = Modifier.heightIn(min = 48.dp).testTag("map_zoom_out")) { Text(stringResource(R.string.project_map_zoom_out)) }
            TextButton(onClick = { zoom = 0.75f; panX = 0f; panY = -layout.nodes.first().y * zoom + 48f },
                modifier = Modifier.heightIn(min = 48.dp).testTag("map_reset")) { Text(stringResource(R.string.project_map_reset)) }
        }
        Text(stringResource(R.string.project_task_dependency_legend), style = MaterialTheme.typography.labelSmall)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("map_viewport")
            .pointerInput(projectId) {
                detectTransformGestures { centroid, pan, scale, _ ->
                    val next = (zoom * scale).coerceIn(ProjectTaskMapLayout.MIN_ZOOM, 2f)
                    val ratio = next / zoom
                    panX = centroid.x / density - (centroid.x / density - panX) * ratio + pan.x / density
                    panY = centroid.y / density - (centroid.y / density - panY) * ratio + pan.y / density
                    zoom = next
                }
            }) {
            LaunchedEffect(projectId) { panY = -layout.nodes.first().y * zoom + 48f }
            val viewportWidth = maxWidth.value
            val viewportHeight = maxHeight.value
            val visible = layout.visible(-panX / zoom, -panY / zoom, (viewportWidth - panX) / zoom, (viewportHeight - panY) / zoom)
            Canvas(Modifier.fillMaxSize()) {
                fun point(x: Float, y: Float) = Offset((x * zoom + panX) * density, (y * zoom + panY) * density)
                layout.edges.forEach { edge ->
                    val from = positions[edge.from] ?: return@forEach
                    val to = positions[edge.to] ?: return@forEach
                    val a = point(from.x + ProjectTaskMapLayout.NODE_WIDTH, from.y + ProjectTaskMapLayout.NODE_HEIGHT / 2)
                    val b = point(to.x, to.y + ProjectTaskMapLayout.NODE_HEIGHT / 2)
                    if (maxOf(a.x, b.x) < 0 || minOf(a.x, b.x) > size.width || maxOf(a.y, b.y) < 0 || minOf(a.y, b.y) > size.height) return@forEach
                    val midX = (a.x + b.x) / 2f
                    val effect = if (edge.dependency) PathEffect.dashPathEffect(floatArrayOf(8f * density, 4f * density)) else null
                    val color = if (edge.dependency) dependencyColor else edgeColor
                    drawLine(color, a, Offset(midX, a.y), 2f * density, pathEffect = effect)
                    drawLine(color, Offset(midX, a.y), Offset(midX, b.y), 2f * density, pathEffect = effect)
                    drawLine(color, Offset(midX, b.y), b, 2f * density, pathEffect = effect)
                }
            }
            visible.forEach { node -> key(node.id) {
                val task = byId[node.id]
                Surface(shape = MaterialTheme.shapes.medium,
                    color = if (node.id == selectedId || task == null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.offset((node.x * zoom + panX).dp, (node.y * zoom + panY).dp)
                        .width((ProjectTaskMapLayout.NODE_WIDTH * zoom).dp)
                        .height((ProjectTaskMapLayout.NODE_HEIGHT * zoom).dp)
                        .testTag("map_node_" + node.id).then(if (task != null) Modifier.clickable { onSelect(node.id) } else Modifier)) {
                    Column(Modifier.padding(Spacing.xs)) {
                        Text(task?.title ?: projectTitle, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        if (task != null) Text(projectTaskStatusLabel(task.status), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                }
            } }
        }
    }
}
