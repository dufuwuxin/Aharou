package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskMapLayout
import com.aharou.feature.agent.domain.task.ProjectTaskStatus
import com.aharou.feature.agent.domain.task.ProjectWorkflowPlan

private const val DEFAULT_ZOOM = 0.85f
private const val MAX_ZOOM = 2f
private const val ZOOM_STEP = 1.25f
private const val EDGE_WIDTH_DP = 2f
private const val DASH_ON_DP = 8f
private const val DASH_OFF_DP = 4f
private const val ARRIVAL_DOT_DP = 3f

/**
 * Bounded physical viewport over a logical layout. Nodes are composed at their logical size under a zoomed
 * [Density], so text, padding and the status bar scale together; tapping a task opens its process in a sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectTaskMap(projectId: String, projectTitle: String, tasks: List<ProjectTask>, selectedId: String?,
    onSelect: (String) -> Unit, onDismiss: () -> Unit, onSupplement: (ProjectTask?) -> Unit,
    onAddChild: (ProjectTask?) -> Unit, modifier: Modifier = Modifier,
    process: @Composable (ProjectTask) -> Unit = {}) {
    val byId = remember(tasks) { tasks.associateBy { it.id } }
    val outer = LocalDensity.current
    val density = outer.density
    var zoom by remember(projectId) { mutableFloatStateOf(DEFAULT_ZOOM) }
    var panX by remember(projectId) { mutableFloatStateOf(0f) }
    var panY by remember(projectId) { mutableFloatStateOf(0f) }
    var reset by remember { mutableIntStateOf(0) }
    var menuId by remember(projectId) { mutableStateOf<String?>(null) }
    val edgeColor = MaterialTheme.colorScheme.outlineVariant
    val dependencyColor = MaterialTheme.colorScheme.tertiary
    val titleStyle = MaterialTheme.typography.labelMedium
    val rootStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    val glyph = fontDp(titleStyle.fontSize, outer.fontScale)
    val rootGlyph = fontDp(rootStyle.fontSize, outer.fontScale)
    val rootId = ProjectTaskMapLayout.rootId(projectId)
    val layout = remember(projectId, tasks, projectTitle, glyph, rootGlyph) {
        ProjectTaskMapLayout.build(projectId, tasks, rootTitle = projectTitle, glyph = glyph, rootGlyph = rootGlyph,
            siblingGap = Spacing.lg.value, levelGap = Spacing.xxl.value * 2, margin = Spacing.lg.value)
    }
    val zoomed = remember(outer, zoom) { Density(density * zoom, outer.fontScale) }
    val path = remember { Path() }
    Column(modifier) {
        Text(stringResource(R.string.project_task_dependency_legend), style = MaterialTheme.typography.labelSmall)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("map_viewport").pointerInput(projectId) {
            detectTransformGestures { centroid, pan, scale, _ ->
                val next = (zoom * scale).coerceIn(ProjectTaskMapLayout.MIN_ZOOM, MAX_ZOOM)
                val ratio = next / zoom
                panX = centroid.x / density - (centroid.x / density - panX) * ratio + pan.x / density
                panY = centroid.y / density - (centroid.y / density - panY) * ratio + pan.y / density
                zoom = next
            }
        }) {
            val viewW = maxWidth.value
            val viewH = maxHeight.value
            fun zoomAtCenter(target: Float) {
                val next = target.coerceIn(ProjectTaskMapLayout.MIN_ZOOM, MAX_ZOOM)
                val ratio = next / zoom
                panX = viewW / 2f - (viewW / 2f - panX) * ratio
                panY = viewH / 2f - (viewH / 2f - panY) * ratio
                zoom = next
            }
            // Root sits at the left edge, vertically centred; re-run once when the first planned tasks arrive.
            LaunchedEffect(projectId, reset, tasks.isEmpty()) {
                val root = layout.node(rootId) ?: return@LaunchedEffect
                panX = Spacing.lg.value - root.x * zoom
                panY = viewH / 2f - (root.y + root.height / 2f) * zoom
            }
            // The process opens in a sheet, so the node no longer needs top-left room; only bring it into view
            // when it is (partly) off-screen, e.g. a selection restored from the tree view.
            LaunchedEffect(selectedId) {
                val node = layout.node(selectedId) ?: return@LaunchedEffect
                val left = node.x * zoom + panX
                val top = node.y * zoom + panY
                if (left < 0f || top < 0f || left + node.width * zoom > viewW || top + node.height * zoom > viewH) {
                    panX = viewW / 2f - (node.x + node.width / 2f) * zoom
                    panY = viewH / 2f - (node.y + node.height / 2f) * zoom
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = EDGE_WIDTH_DP * density)
                val dashed = Stroke(width = EDGE_WIDTH_DP * density,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON_DP * density, DASH_OFF_DP * density)))
                fun sx(x: Float) = (x * zoom + panX) * density
                fun sy(y: Float) = (y * zoom + panY) * density
                val left = -panX / zoom
                val top = -panY / zoom
                val right = (viewW - panX) / zoom
                val bottom = (viewH - panY) / zoom
                layout.edges.forEach { edge ->
                    val curve = layout.curve(edge) ?: return@forEach
                    if (!curve.intersects(left, top, right, bottom)) return@forEach
                    path.reset()
                    path.moveTo(sx(curve.x0), sy(curve.y0))
                    path.cubicTo(sx(curve.x1), sy(curve.y1), sx(curve.x2), sy(curve.y2), sx(curve.x3), sy(curve.y3))
                    val color = if (edge.dependency) dependencyColor else edgeColor
                    drawPath(path, color, style = if (edge.dependency) dashed else stroke)
                    if (edge.dependency) drawCircle(color, ARRIVAL_DOT_DP * density, Offset(sx(curve.x3), sy(curve.y3)))
                }
            }
            layout.visible(-panX / zoom, -panY / zoom, (viewW - panX) / zoom, (viewH - panY) / zoom).forEach { node -> key(node.id) {
                val task = byId[node.id]
                Box(Modifier.offset((node.x * zoom + panX).dp, (node.y * zoom + panY).dp)
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize((node.width * zoom).dp, (node.height * zoom).dp)
                    .testTag("map_node_" + node.id)) {
                    CompositionLocalProvider(LocalDensity provides zoomed) {
                        if (task == null) ProjectTaskMapNode(projectTitle,
                            stringResource(R.string.project_flow_progress, tasks.count { ProjectWorkflowPlan.delivered(it) }, tasks.size),
                            root = true, selected = false, accent = MaterialTheme.colorScheme.primary, titleStyle = rootStyle,
                            onClick = { menuId = node.id }, onLongClick = { menuId = node.id })
                        else ProjectTaskMapNode(task.title,
                            stringResource(R.string.project_map_node_meta, projectTaskStatusLabel(task.status),
                                task.owner.ifBlank { stringResource(R.string.project_flow_default_member) },
                                ProjectWorkflowPlan.participants(task).size),
                            root = false, selected = node.id == selectedId, accent = projectTaskStatusColor(task.status),
                            titleStyle = titleStyle, onClick = { onSelect(node.id) }, onLongClick = { menuId = node.id })
                    }
                    ProjectTaskNodeMenu(expanded = menuId == node.id, onDismiss = { menuId = null },
                        onSupplement = { menuId = null; onSupplement(task) }, onAddChild = { menuId = null; onAddChild(task) })
                }
            } }
            Surface(shape = RoundedCornerShape(Radius.pill), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(onClick = { zoomAtCenter(zoom * ZOOM_STEP) }, modifier = Modifier.heightIn(min = 48.dp).testTag("map_zoom_in")) { Text(stringResource(R.string.project_map_zoom_in)) }
                    TextButton(onClick = { zoomAtCenter(zoom / ZOOM_STEP) }, modifier = Modifier.heightIn(min = 48.dp).testTag("map_zoom_out")) { Text(stringResource(R.string.project_map_zoom_out)) }
                    TextButton(onClick = { zoom = DEFAULT_ZOOM; reset++ }, modifier = Modifier.heightIn(min = 48.dp).testTag("map_reset")) { Text(stringResource(R.string.project_map_reset)) }
                }
            }
        }
    }
    val selected = byId[selectedId]
    if (selected != null) AdaptiveModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("map_process_sheet")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.size(Spacing.sm).background(projectTaskStatusColor(selected.status), CircleShape))
                Text(selected.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Text(projectTaskStatusLabel(selected.status), style = MaterialTheme.typography.labelMedium)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { process(selected) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectTaskMapNode(title: String, meta: String, root: Boolean, selected: Boolean, accent: Color,
    titleStyle: androidx.compose.ui.text.TextStyle, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(Radius.mdLarge),
        color = when { root -> colors.primaryContainer; selected -> colors.secondaryContainer; else -> colors.surfaceContainerHigh },
        contentColor = if (root) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
            Box(Modifier.fillMaxHeight().width(Spacing.xs).background(accent))
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalArrangement = Arrangement.Center) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = titleStyle)
                Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                    color = if (root) colors.onPrimaryContainer else colors.onSurfaceVariant)
            }
        }
    }
}

/** Status accent shared by map nodes and the process sheet; colours come only from the theme. */
@Composable
internal fun projectTaskStatusColor(status: ProjectTaskStatus): Color = when (status) {
    ProjectTaskStatus.DRAFT -> MaterialTheme.colorScheme.outlineVariant
    ProjectTaskStatus.READY -> MaterialTheme.colorScheme.outline
    ProjectTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
    ProjectTaskStatus.REVIEW -> MaterialTheme.semanticColors.warning
    ProjectTaskStatus.DONE -> MaterialTheme.semanticColors.success
    ProjectTaskStatus.BLOCKED -> MaterialTheme.colorScheme.error
}

/** Logical width per glyph in dp (sp × font scale), falling back when the style leaves the size unspecified. */
private fun fontDp(size: TextUnit, fontScale: Float): Float =
    if (size.isSp) size.value * fontScale else ProjectTaskMapLayout.DEFAULT_GLYPH * fontScale
