package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
internal fun TeamFlowMap(admins: List<String>, stages: List<AgentTeamStageForm>, routes: List<TeamRoute>) {
    val rows = stages.map { stage -> stage to stage.tasks.filter { it.prompt.isNotBlank() } }
    val count = maxOf(1, rows.maxOfOrNull { it.second.size } ?: 1)
    val width = count * 240 + 96
    val height = rows.size * 160 + 128
    val positions = buildMap { rows.forEachIndexed { stageIndex, (stage, tasks) -> tasks.forEachIndexed { memberIndex, task ->
        put(TeamFlowNode(stage.id, task.id), memberIndex to stageIndex)
    } } }
    val executeColor = MaterialTheme.colorScheme.primary
    val reviewColor = MaterialTheme.colorScheme.tertiary
    val rejectColor = MaterialTheme.colorScheme.error
    val organizationColor = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (admins.size != 1) Text(stringResource(R.string.team_flow_admin_required), color = rejectColor)
        Text(stringResource(if (routes.isEmpty()) R.string.team_flow_legacy else R.string.team_flow_routed), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.team_flow_organization_legend), style = MaterialTheme.typography.labelSmall)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(width.dp).height(height.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = width.dp.toPx() / 2
                    rows.forEachIndexed { stageIndex, (_, tasks) -> tasks.forEachIndexed { memberIndex, _ ->
                        val x = (memberIndex * 240 + 144).dp.toPx()
                        val y = (stageIndex * 160 + 120).dp.toPx()
                        val path = Path().apply { moveTo(center, 72.dp.toPx()); lineTo(center, 88.dp.toPx()); lineTo(x, 88.dp.toPx()); lineTo(x, y) }
                        drawPath(path, organizationColor, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
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
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.offset(x = (width / 2 - 108).dp, y = 12.dp).width(216.dp).height(60.dp)) {
                    Text(stringResource(R.string.team_flow_admin, admins.singleOrNull().orEmpty()), Modifier.padding(Spacing.md), style = MaterialTheme.typography.titleSmall)
                }
                rows.forEachIndexed { stageIndex, (stage, tasks) -> tasks.forEachIndexed { memberIndex, task ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.offset(x = (memberIndex * 240 + 36).dp, y = (stageIndex * 160 + 120).dp).width(216.dp).height(88.dp)) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text(stage.title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            Text(task.agent.ifBlank { task.department }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            val actions = routes.filter { it.to == TeamFlowNode(stage.id, task.id) }.map { it.action }.distinct().map { teamRouteActionLabel(it) }
                            Text(actions.joinToString(" / "), style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
