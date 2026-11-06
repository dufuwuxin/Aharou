package com.aharou.feature.agent.presentation.component

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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
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
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskMapLayout
import com.aharou.feature.agent.domain.task.ProjectTaskOutline
import com.aharou.feature.agent.domain.task.ProjectTaskStatus
import com.aharou.feature.agent.domain.task.ProjectWorkflowPlan
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Search
import compose.icons.feathericons.X
import kotlin.math.abs

private const val DEFAULT_ZOOM = 0.9f
private const val MAX_ZOOM = 2f
private const val ZOOM_STEP = 1.25f
private const val EDGE_DP = 1.5f
private const val PATH_EDGE_DP = 2.5f
private const val RING_DP = 1.5f
private const val DASH_ON_DP = 6f
private const val DASH_OFF_DP = 4f
private const val FORK_DOT = 2.5f
private const val ROOT_DOT = 7f
private const val ROOT_GLOW = 14f
private const val RUN_GLOW = 10f
private const val INNER_DOT = 0.4f
private const val GLOW_ALPHA = 0.18f
private const val DIM_ALPHA = 0.32f
/** Header placed at this fraction of the viewport height when brought into view, leaving room for its outline. */
private const val FOCUS_ROW = 1f / 3f

private val StringSetSaver = Saver<Set<String>, ArrayList<String>>(save = { ArrayList(it) }, restore = { it.toSet() })

private data class DotStyle(val color: Color, val filled: Boolean, val glow: Boolean = false, val inner: Boolean = false)

/**
 * Mind map in the style of a branching outline: trunk, thin fanning curves, a status dot at each branch end and one
 * line of title. Tapping a task unfolds it in place into one dot per line ([ProjectTaskOutlineView]); several nodes
 * can be unfolded at once. Unfold and search state live here (saveable), never in the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectTaskMap(projectId: String, projectTitle: String, tasks: List<ProjectTask>, processId: String?,
    onOpenProcess: (String) -> Unit, onDismissProcess: () -> Unit, onSupplement: (ProjectTask?) -> Unit,
    onAddChild: (ProjectTask?) -> Unit, modifier: Modifier = Modifier,
    onContentDrag: () -> Unit = {}, onOpenRoot: () -> Unit = {}, rootConversation: @Composable () -> Unit = {},
    process: @Composable (ProjectTask) -> Unit = {}) {
    val onDrag by rememberUpdatedState(onContentDrag)
    val byId = remember(tasks) { tasks.associateBy { it.id } }
    val outlines = remember(tasks) { tasks.associate { it.id to ProjectTaskOutline.of(it, tasks) } }
    val outer = LocalDensity.current
    val density = outer.density
    var viewportWidth by remember(projectId) { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    var zoom by remember(projectId) { mutableFloatStateOf(DEFAULT_ZOOM) }
    var panX by remember(projectId) { mutableFloatStateOf(0f) }
    var panY by remember(projectId) { mutableFloatStateOf(0f) }
    var reset by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable(projectId, stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var openGroups by rememberSaveable(projectId, stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var fullLines by rememberSaveable(projectId, stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var query by rememberSaveable(projectId) { mutableStateOf("") }
    var focusId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    // Search hits unfold automatically; a hit the user folds again stays folded until the query changes.
    var suppressed by remember(projectId, query) { mutableStateOf(emptySet<String>()) }
    val measured = remember(projectId) { mutableStateMapOf<String, Float>() }
    val searching = query.isNotBlank()
    val hits = remember(tasks, query) { ProjectTaskOutline.find(tasks, query) }
    val hitIds = remember(hits) { hits.mapTo(HashSet()) { it.taskId } }
    // Hit carries no line index, so equal lines are matched by value (duplicates all light up).
    val hitLines: Map<String, Set<Int>> = remember(hits, outlines) {
        hits.groupBy({ it.taskId }, { it.line }).mapValues { (id, found) ->
            val wanted = found.filterNotNull().toSet()
            outlines[id].orEmpty().withIndex().filter { it.value in wanted }.mapTo(HashSet()) { it.index }
        }
    }
    val autoGroups = remember(hitLines, outlines) {
        hitLines.flatMapTo(HashSet()) { (id, indices) ->
            val lines = outlines[id].orEmpty()
            indices.mapNotNull { i ->
                val kind = lines[i].kind
                if (lines.take(i).count { it.kind == kind } >= OUTLINE_FOLD) "$id#${kind.name}" else null
            }
        }
    }
    val shown = (expanded + hitIds - suppressed).filterTo(HashSet()) { it in byId }
    val groupsOpen = openGroups + autoGroups
    fun kindsOpen(id: String) = ProjectTaskOutline.Kind.entries.filterTo(HashSet()) { "$id#${it.name}" in groupsOpen }
    fun linesOpen(id: String) = fullLines.mapNotNullTo(HashSet()) { k ->
        if (k.substringBeforeLast('#') == id) k.substringAfterLast('#').toIntOrNull() else null
    }
    // Measured height wins only when larger, so a stale or not-yet-measured node never overlaps its neighbours.
    val heights = shown.associateWith { id ->
        maxOf(measured[id] ?: 0f, estimateOutlineHeight(outlines[id].orEmpty(), kindsOpen(id)))
    }
    val titleStyle = MaterialTheme.typography.labelLarge
    val rootStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    val glyph = fontDp(titleStyle.fontSize, outer.fontScale)
    val rootGlyph = fontDp(rootStyle.fontSize, outer.fontScale)
    val headerLimit = ((viewportWidth - Spacing.lg.value * 2) / DEFAULT_ZOOM - Spacing.xxl.value * 2) / 2
    val layout = remember(projectId, tasks, projectTitle, heights, glyph, rootGlyph, headerLimit) {
        ProjectTaskMapLayout.build(projectId, tasks, heights, projectTitle, glyph, rootGlyph,
            siblingGap = Spacing.sm.value, levelGap = Spacing.xxl.value * 2, margin = Spacing.lg.value,
            maxHeaderWidth = headerLimit)
    }
    fun chain(id: String?) = generateSequence(id?.takeIf { it in byId }) { byId[it]?.parentId?.takeIf { p -> p in byId } }
        .take(tasks.size + 1)
    val pathIds = remember(focusId, tasks) { chain(focusId).toSet() }
    val hitPath = remember(hitIds, tasks) { hitIds.flatMapTo(HashSet()) { chain(it) } }
    val colors = MaterialTheme.colorScheme
    val weakLine = colors.outlineVariant
    val dependencyLine = colors.outline
    val accent = colors.primary
    fun dotStyle(status: ProjectTaskStatus, onPath: Boolean): DotStyle = when (status) {
        ProjectTaskStatus.BLOCKED -> DotStyle(colors.error, filled = true)
        ProjectTaskStatus.RUNNING -> DotStyle(accent, filled = true, glow = true)
        ProjectTaskStatus.REVIEW -> DotStyle(if (onPath) accent else colors.onSurfaceVariant, filled = false, inner = true)
        ProjectTaskStatus.DONE -> DotStyle(if (onPath) accent else colors.onSurfaceVariant, filled = true)
        ProjectTaskStatus.READY -> DotStyle(if (onPath) accent else colors.outline, filled = false)
        ProjectTaskStatus.DRAFT -> DotStyle(if (onPath) accent else colors.outlineVariant, filled = false)
    }
    fun toggle(id: String) {
        focusId = id
        if (id in shown) {
            expanded = expanded - id
            if (id in hitIds) suppressed = suppressed + id
            measured.remove(id)
        } else { expanded = expanded + id; suppressed = suppressed - id }
    }
    val zoomed = remember(outer, zoom) { Density(density * zoom, outer.fontScale) }
    val path = remember { Path() }
    Column(modifier.onSizeChanged { viewportWidth = it.width / density }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val clear: (@Composable () -> Unit)? = if (query.isEmpty()) null else { {
                IconButton(onClick = { query = "" }) {
                    Icon(FeatherIcons.X, stringResource(R.string.project_map_search_clear), modifier = Modifier.size(Spacing.lg))
                }
            } }
            AppTextField(query, { query = it }, modifier = Modifier.weight(1f).testTag("map_search"),
                placeholder = stringResource(R.string.project_map_search),
                leadingIcon = { Icon(FeatherIcons.Search, null, modifier = Modifier.size(Spacing.lg)) }, trailingIcon = clear)
            val allOpen = tasks.isNotEmpty() && tasks.all { it.id in expanded }
            TextButton(onClick = {
                if (allOpen) { expanded = emptySet(); measured.clear() } else expanded = tasks.mapTo(HashSet()) { it.id }
                suppressed = emptySet()
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("map_expand_all")) {
                Text(stringResource(if (allOpen) R.string.project_task_collapse_all else R.string.project_task_expand_all))
            }
        }
        Text(if (searching) stringResource(R.string.project_map_search_count, hitIds.size, hits.size)
            else stringResource(R.string.project_task_dependency_legend),
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("map_hint"))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("map_viewport").pointerInput(projectId, layout.width, layout.height) {
            detectTransformGestures { centroid, pan, scale, _ ->
                onDrag()
                if (!scale.isFinite() || scale <= 0f || !pan.x.isFinite() || !pan.y.isFinite() ||
                    !centroid.x.isFinite() || !centroid.y.isFinite()) return@detectTransformGestures
                val minimum = ProjectTaskMapLayout.minimumZoom(ProjectTaskMapLayout.fitZoom(
                    size.width / density, size.height / density, layout.width, layout.height, MAX_ZOOM))
                val next = ProjectTaskMapLayout.clampZoom(zoom * scale, minimum, MAX_ZOOM)
                val ratio = next / zoom
                panX = centroid.x / density - (centroid.x / density - panX) * ratio + pan.x / density
                panY = centroid.y / density - (centroid.y / density - panY) * ratio + pan.y / density
                zoom = next
            }
        }) {
            val viewW = maxWidth.value
            val viewH = maxHeight.value
            fun zoomAtCenter(target: Float) {
                val minimum = ProjectTaskMapLayout.minimumZoom(ProjectTaskMapLayout.fitZoom(
                    viewW, viewH, layout.width, layout.height, MAX_ZOOM))
                val next = ProjectTaskMapLayout.clampZoom(target, minimum, MAX_ZOOM)
                val ratio = next / zoom
                panX = viewW / 2f - (viewW / 2f - panX) * ratio
                panY = viewH / 2f - (viewH / 2f - panY) * ratio
                zoom = next
            }
            // Only pans when the header row is (partly) off-screen; an unfolded outline gets the lower two thirds.
            fun bringIntoView(id: String?) {
                val node = layout.node(id) ?: return
                val left = node.x * zoom + panX
                val top = node.y * zoom + panY
                if (left >= 0f && top >= 0f && left + node.headerWidth * zoom <= viewW &&
                    top + ProjectTaskMapLayout.NODE_HEIGHT * zoom <= viewH) return
                panX = viewW / 2f - (node.x + node.headerWidth / 2f) * zoom
                panY = viewH * FOCUS_ROW - node.dotY * zoom
            }
            LaunchedEffect(projectId, reset, tasks.isEmpty()) {
                val root = layout.node(ProjectTaskMapLayout.rootId(projectId)) ?: return@LaunchedEffect
                panX = Spacing.lg.value - root.x * zoom
                panY = viewH / 2f - root.dotY * zoom
            }
            LaunchedEffect(focusId) { bringIntoView(focusId) }
            val firstHit = layout.nodes.firstOrNull { it.id in hitIds }?.id
            LaunchedEffect(query, firstHit) { if (searching) bringIntoView(firstHit) }
            val left = -panX / zoom
            val top = -panY / zoom
            val right = (viewW - panX) / zoom
            val bottom = (viewH - panY) / zoom
            val visible = layout.visible(left, top, right, bottom)
            Canvas(Modifier.fillMaxSize()) {
                fun sx(x: Float) = (x * zoom + panX) * density
                fun sy(y: Float) = (y * zoom + panY) * density
                val thin = Stroke(EDGE_DP * density)
                val bold = Stroke(PATH_EDGE_DP * density)
                val dashed = Stroke(EDGE_DP * density,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON_DP * density, DASH_OFF_DP * density)))
                val sources = HashMap<String, Boolean>()
                layout.edges.forEach { edge ->
                    val emphasised = !edge.dependency && edge.to in pathIds
                    sources[edge.from] = sources[edge.from] == true || emphasised
                    val curve = layout.curve(edge) ?: return@forEach
                    if (!curve.intersects(left, top, right, bottom)) return@forEach
                    val dim = searching && if (edge.dependency) edge.from !in hitIds && edge.to !in hitIds else edge.to !in hitPath
                    val color = when { emphasised -> accent; edge.dependency -> dependencyLine; else -> weakLine }
                    path.reset()
                    path.moveTo(sx(curve.x0), sy(curve.y0))
                    path.cubicTo(sx(curve.x1), sy(curve.y1), sx(curve.x2), sy(curve.y2), sx(curve.x3), sy(curve.y3))
                    drawPath(path, color, alpha = if (dim) DIM_ALPHA else 1f,
                        style = when { emphasised -> bold; edge.dependency -> dashed; else -> thin })
                    if (edge.dependency && layout.node(edge.to)?.forkX == curve.x3) {
                        drawCircle(color, FORK_DOT * zoom * density, Offset(sx(curve.x3), sy(curve.y3)), alpha = if (dim) DIM_ALPHA else 1f)
                    }
                }
                // Trunk from the right end of the header row (badge and arrow) to the fork point where branches fan out.
                sources.forEach { (id, emphasised) ->
                    val node = layout.node(id) ?: return@forEach
                    if (node.forkX < left || node.x > right || node.dotY < top || node.dotY > bottom) return@forEach
                    val alpha = if (searching && !node.root && id !in hitPath) DIM_ALPHA else 1f
                    val color = if (emphasised) accent else weakLine
                    val y = sy(node.dotY)
                    drawLine(color, Offset(sx(node.x + node.width), y), Offset(sx(node.forkX), y),
                        (if (emphasised) PATH_EDGE_DP else EDGE_DP) * density, alpha = alpha)
                    drawCircle(color, FORK_DOT * zoom * density, Offset(sx(node.forkX), y), alpha = alpha)
                }
                visible.forEach { node ->
                    val c = Offset(sx(node.dotX), sy(node.dotY))
                    if (node.root) {
                        drawCircle(accent, ROOT_GLOW * zoom * density, c, alpha = GLOW_ALPHA)
                        drawCircle(accent, ROOT_DOT * zoom * density, c)
                        return@forEach
                    }
                    val task = byId[node.id] ?: return@forEach
                    val alpha = if (searching && node.id !in hitIds) DIM_ALPHA else 1f
                    val r = ProjectTaskMapLayout.DOT_RADIUS * zoom * density
                    if (node.id in shown) drawLine(weakLine, Offset(c.x, c.y + r), Offset(c.x, sy(node.y + node.height - Spacing.sm.value)),
                        EDGE_DP * density, alpha = alpha)
                    val style = dotStyle(task.status, node.id in pathIds)
                    if (style.glow) drawCircle(style.color, RUN_GLOW * zoom * density, c, alpha = GLOW_ALPHA * alpha)
                    if (style.filled) drawCircle(style.color, r, c, alpha = alpha)
                    else {
                        drawCircle(style.color, (r - RING_DP * density / 2f).coerceAtLeast(0f), c, alpha = alpha, style = Stroke(RING_DP * density))
                        if (style.inner) drawCircle(style.color, r * INNER_DOT, c, alpha = alpha)
                    }
                }
            }
            visible.forEach { node -> key(node.id) {
                val task = byId[node.id]
                val unfolded = task != null && node.id in shown
                Box(Modifier.offset((node.x * zoom + panX).dp, (node.y * zoom + panY).dp)
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .width((node.width * zoom).dp)
                    .then(if (unfolded) Modifier.onSizeChanged { size ->
                        val h = size.height / (density * zoom)
                        if (abs((measured[node.id] ?: 0f) - h) > 0.5f) measured[node.id] = h
                    } else Modifier.height((ProjectTaskMapLayout.NODE_HEIGHT * zoom).dp))
                    .alpha(if (searching && task != null && node.id !in hitIds) DIM_ALPHA else 1f)
                    .testTag("map_node_" + node.id)) {
                    CompositionLocalProvider(LocalDensity provides zoomed) {
                        if (task == null) ProjectTaskMapRootHeader(projectTitle,
                            stringResource(R.string.project_flow_progress, tasks.count { ProjectWorkflowPlan.delivered(it) }, tasks.size),
                            rootStyle, onClick = onOpenRoot)
                        else Column {
                            ProjectTaskMapHeader(task.title, projectTaskStatusLabel(task.status), outlines[task.id].orEmpty().size, unfolded,
                                emphasised = node.id in pathIds || node.id in hitIds || task.status == ProjectTaskStatus.RUNNING,
                                titleStyle = titleStyle, onClick = { toggle(task.id) }, onLongClick = { toggle(task.id) })
                            if (unfolded) ProjectTaskOutlineView(outlines[task.id].orEmpty(), kindsOpen(task.id), linesOpen(task.id),
                                hitLines[task.id].orEmpty(),
                                onToggleGroup = { kind -> val k = "${task.id}#${kind.name}"; openGroups = if (k in openGroups) openGroups - k else openGroups + k },
                                onToggleLine = { i -> val k = "${task.id}#$i"; fullLines = if (k in fullLines) fullLines - k else fullLines + k },
                                onJump = { focusId = it }, onOpenProcess = { onOpenProcess(task.id) },
                                modifier = Modifier.fillMaxWidth().testTag("map_outline_" + task.id))
                        }
                    }
                }
            } }
            Surface(shape = RoundedCornerShape(Radius.pill), color = colors.surfaceContainerHigh,
                modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(onClick = { zoomAtCenter(zoom * ZOOM_STEP) }, modifier = Modifier.heightIn(min = 48.dp).testTag("map_zoom_in")) { Text(stringResource(R.string.project_map_zoom_in)) }
                    TextButton(onClick = { zoomAtCenter(zoom / ZOOM_STEP) }, modifier = Modifier.heightIn(min = 48.dp).testTag("map_zoom_out")) { Text(stringResource(R.string.project_map_zoom_out)) }
                    TextButton(onClick = {
                        zoom = ProjectTaskMapLayout.fitZoom(viewW - Spacing.lg.value * 2,
                            viewH - Spacing.lg.value * 2, layout.width, layout.height, MAX_ZOOM)
                        panX = (viewW - layout.width * zoom) / 2f
                        panY = (viewH - layout.height * zoom) / 2f
                    }, modifier = Modifier.heightIn(min = 48.dp).testTag("map_fit")) { Text(stringResource(R.string.project_map_fit)) }
                    TextButton(onClick = { zoom = DEFAULT_ZOOM; reset++ }, modifier = Modifier.heightIn(min = 48.dp).testTag("map_reset")) { Text(stringResource(R.string.project_map_reset)) }
                }
            }
        }
    }
    if (processId == ProjectTaskMapLayout.rootId(projectId)) AdaptiveModalBottomSheet(onDismissRequest = onDismissProcess,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("map_root_conversation")) {
            Text(projectTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(Spacing.lg))
            Box(Modifier.weight(1f).fillMaxWidth()) { rootConversation() }
        }
    }
    val selected = byId[processId]
    if (selected != null) AdaptiveModalBottomSheet(onDismissRequest = onDismissProcess,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("map_process_sheet")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.size(Spacing.sm).background(projectTaskStatusColor(selected.status), CircleShape))
                Text(selected.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { onAddChild(selected) }) { Text(stringResource(R.string.project_node_add_child)) }
                Text(projectTaskStatusLabel(selected.status), style = MaterialTheme.typography.labelMedium)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { process(selected) }
        }
    }
}

/** Header row: room for the canvas-drawn status dot, one-line title, line-count badge and unfold arrow.
 *  Zoomed-out nodes are overview targets; zoom in for detailed interaction. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectTaskMapHeader(title: String, status: String, lineCount: Int, unfolded: Boolean, emphasised: Boolean,
    titleStyle: TextStyle, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(ProjectTaskMapLayout.NODE_HEIGHT.dp)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .semantics(mergeDescendants = true) { stateDescription = status }
        .padding(end = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(Spacing.xxl))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = titleStyle,
            color = if (emphasised) colors.primary else colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (lineCount > 0) Surface(shape = RoundedCornerShape(Radius.pill), color = MaterialTheme.semanticColors.capsuleSurface,
            contentColor = colors.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.xs)) {
            Text(lineCount.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = Spacing.xs))
        }
        Icon(if (unfolded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight, null, tint = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xs).size(Spacing.lg))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectTaskMapRootHeader(title: String, progress: String, style: TextStyle, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(ProjectTaskMapLayout.NODE_HEIGHT.dp)
        .combinedClickable(onClick = onClick, onLongClick = onClick)
        .padding(end = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(Spacing.xxl))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style, color = MaterialTheme.colorScheme.onSurface)
            Text(progress, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Status colour for the process sheet header; colours come only from the theme. */
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
