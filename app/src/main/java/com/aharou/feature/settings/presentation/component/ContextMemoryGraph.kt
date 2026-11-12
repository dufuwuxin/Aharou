package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.context.ContextMemoryEdge
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextMemoryGraph as MemoryGraph
import com.aharou.feature.agent.domain.context.ContextMemoryResolution
import com.aharou.feature.agent.domain.context.WorldbookRelationType

@Composable
internal fun ContextMemoryGraph(entries: List<ContextEntry>, onSelect: (ContextEntry) -> Unit, onEditRules: () -> Unit) {
    val graph = remember(entries) { MemoryGraph.project(entries) }
    var nodePage by rememberSaveable(entries.map { it.id }) { mutableStateOf(0) }
    var relationPage by rememberSaveable(nodePage, graph) { mutableStateOf(0) }
    val page = graph.page(nodePage)
    val byId = remember(graph) { graph.nodes.associateBy { it.id } }
    val relationCount = ((page.edges.size + 11) / 12).coerceAtLeast(1)
    val currentRelationPage = relationPage.coerceIn(0, relationCount - 1)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Text(stringResource(R.string.context_graph_title), style = MaterialTheme.typography.titleLarge)
        ContextNotice(stringResource(R.string.context_graph_help))
        Text(stringResource(R.string.context_graph_summary, graph.nodes.size, graph.edges.size), style = MaterialTheme.typography.labelLarge)
        if (graph.edges.isEmpty()) {
            ContextNotice(stringResource(R.string.context_graph_empty))
            TextButton(onClick = onEditRules) { Text(stringResource(R.string.context_worldbook_edit_rules)) }
        }
        if (page.nodes.isNotEmpty()) {
            ContextGraphCanvas(page.nodes, page.edges.filter { it.targetId in page.nodes.map(ContextEntry::id) }
                .take(24), onSelect)
            ContextGraphPager(page.index, page.count, { nodePage = it }, stringResource(R.string.context_graph_node_pages))
            SettingsGroup {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.context_graph_nodes), style = MaterialTheme.typography.titleMedium)
                    page.nodes.forEachIndexed { index, entry ->
                        TextButton(onClick = { onSelect(entry) }) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Text(stringResource(R.string.context_graph_node_label, index + 1, entry.title))
                                Text(stringResource(R.string.context_graph_source, stringResource(entry.scope.label()),
                                    stringResource(entry.category.label()), entry.sourceLabel),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.context_graph_relations), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.context_graph_page_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (page.edges.isEmpty()) Text(stringResource(R.string.context_graph_no_page_relations))
        page.edges.drop(currentRelationPage * 12).take(12).forEach { edge ->
            val source = byId.getValue(edge.sourceId)
            val target = edge.targetId?.let(byId::get)
            SettingsGroup {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.context_graph_edge_label, source.title, stringResource(edge.type.graphLabel()),
                        target?.title ?: edge.target), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(when (edge.resolution) {
                        ContextMemoryResolution.RESOLVED -> R.string.context_graph_resolved
                        ContextMemoryResolution.AMBIGUOUS -> R.string.context_graph_ambiguous
                        ContextMemoryResolution.MISSING -> R.string.context_graph_missing
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.context_graph_target_value, edge.target), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onSelect(source) }) { Text(stringResource(R.string.context_graph_edit_source)) }
                    if (target != null) TextButton(onClick = { onSelect(target) }) {
                        Text(stringResource(R.string.context_graph_open_target))
                    }
                }
            }
        }
        if (page.edges.isNotEmpty()) ContextGraphPager(currentRelationPage, relationCount, { relationPage = it },
            stringResource(R.string.context_graph_relation_pages))
    }
}

@Composable
private fun ContextGraphCanvas(nodes: List<ContextEntry>, links: List<ContextMemoryEdge>, onSelect: (ContextEntry) -> Unit) {
    val measurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onPrimaryContainer)
    val fill = MaterialTheme.colorScheme.primaryContainer
    val line = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val gap = with(density) { Spacing.md.toPx() }
    val corner = with(density) { Radius.mdLarge.toPx() }
    val rows = (nodes.size + 1) / 2
    val height = (96.dp * rows) * density.fontScale.coerceAtLeast(1f)
    val description = stringResource(R.string.context_graph_canvas_help)
    val labels = nodes.mapIndexed { index, entry -> stringResource(R.string.context_graph_node_label, index + 1, entry.title) }
    val edgeLabels = links.groupBy { it.sourceId to it.targetId }.map { (pair, edges) ->
        pair to edges.map { stringResource(it.type.graphLabel()) }.distinct().joinToString(" / ")
    }
    val edgeStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }
        .pointerInput(nodes, onSelect) {
            detectTapGestures { point ->
                val column = (point.x / (size.width / 2f)).toInt().coerceIn(0, 1)
                val row = (point.y / (size.height / rows.toFloat())).toInt().coerceIn(0, rows - 1)
                nodes.getOrNull(row * 2 + column)?.let(onSelect)
            }
        }) {
        val width = size.width / 2f
        val cellHeight = size.height / rows
        fun center(index: Int) = Offset((index % 2 + 0.5f) * width, (index / 2 + 0.5f) * cellHeight)
        val indices = nodes.mapIndexed { index, entry -> entry.id to index }.toMap()
        val boxWidth = width * 0.76f
        val boxHeight = cellHeight - gap * 2
        edgeLabels.forEach { (pair, label) ->
            val from = indices[pair.first] ?: return@forEach
            val to = indices[pair.second] ?: return@forEach
            val source = center(from)
            val target = center(to)
            if (from == to) {
                drawCircle(line, gap, source + Offset(0f, -boxHeight / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            } else {
                val delta = target - source
                val horizontal = from % 2 != to % 2
                val scale = if (horizontal) (boxWidth / 2) / kotlin.math.abs(delta.x)
                    else (boxHeight / 2) / kotlin.math.abs(delta.y)
                val start = source + delta * scale
                val end = target - delta * scale
                drawLine(line, start, end, strokeWidth = 2.dp.toPx())
                val unit = delta / delta.getDistance()
                val back = end - unit * gap
                val side = Offset(-unit.y, unit.x) * (gap / 2)
                drawPath(Path().apply {
                    moveTo(end.x, end.y)
                    lineTo((back + side).x, (back + side).y)
                    lineTo((back - side).x, (back - side).y)
                    close()
                }, line)
                val text = measurer.measure(label, edgeStyle, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (width - gap).toInt().coerceAtLeast(1)))
                val middle = (source + target) / 2f
                drawText(text, topLeft = middle - Offset(text.size.width / 2f, text.size.height.toFloat()))
            }
        }
        nodes.forEachIndexed { index, _ ->
            val start = center(index) - Offset(boxWidth / 2, boxHeight / 2)
            drawRoundRect(fill, start, Size(boxWidth, boxHeight), CornerRadius(corner))
            val text = measurer.measure(labels[index], textStyle, overflow = TextOverflow.Ellipsis,
                maxLines = 2, constraints = Constraints(maxWidth = (boxWidth - gap * 2).toInt().coerceAtLeast(1)))
            drawText(text, topLeft = start + Offset(gap, (boxHeight - text.size.height) / 2f))
        }
    }
}

@Composable
private fun ContextGraphPager(index: Int, count: Int, onPage: (Int) -> Unit, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.context_graph_page, label, index + 1, count), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextButton(enabled = index > 0, onClick = { onPage(index - 1) }) { Text(stringResource(R.string.context_graph_previous)) }
            TextButton(enabled = index + 1 < count, onClick = { onPage(index + 1) }) { Text(stringResource(R.string.common_next)) }
        }
    }
}

private fun WorldbookRelationType.graphLabel(): Int = when (this) {
    WorldbookRelationType.RELATED -> R.string.context_graph_related
    WorldbookRelationType.SUPPORTS -> R.string.context_graph_supports
    WorldbookRelationType.CONTRADICTS -> R.string.context_graph_contradicts
    WorldbookRelationType.SUPERSEDES -> R.string.context_graph_supersedes
}
