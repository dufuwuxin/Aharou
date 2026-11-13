package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.subagent.*

@Composable
internal fun TeamRouteEditor(stages: List<AgentTeamStageForm>, routes: List<TeamRoute>, onChange: (List<TeamRoute>) -> Unit) {
    val nodes = stages.flatMap { stage -> stage.tasks.filter { it.prompt.isNotBlank() }.map { TeamFlowNode(stage.id, it.id) } }
    var from by remember { mutableStateOf<TeamFlowNode?>(null) }
    var to by remember { mutableStateOf<TeamFlowNode?>(null) }
    var action by remember { mutableStateOf(TeamRouteAction.EXECUTE) }
    var fromMenu by remember { mutableStateOf(false) }
    var toMenu by remember { mutableStateOf(false) }
    fun label(node: TeamFlowNode?): String {
        if (node == null) return ""
        val stage = stages.firstOrNull { it.id == node.stageId }
        val task = stage?.tasks?.firstOrNull { it.id == node.taskId }
        val member = task?.let { it.agent.ifBlank { it.department } } ?: node.taskId
        return "${stage?.title ?: node.stageId} · $member"
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.team_flow_routes), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.team_flow_route_hint), style = MaterialTheme.typography.bodySmall)
        if (nodes.size < 2) {
            Text(stringResource(R.string.team_flow_nodes_required), style = MaterialTheme.typography.bodySmall)
        }
        Box {
            OutlinedButton(onClick = { fromMenu = true }, enabled = nodes.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.team_flow_from, label(from))) }
            DropdownMenu(fromMenu, { fromMenu = false }) { nodes.forEach { node ->
                DropdownMenuItem(text = { Text(label(node)) }, onClick = { from = node; fromMenu = false })
            } }
        }
        Box {
            OutlinedButton(onClick = { toMenu = true }, enabled = nodes.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.team_flow_to, label(to))) }
            DropdownMenu(toMenu, { toMenu = false }) { nodes.forEach { node ->
                DropdownMenuItem(text = { Text(label(node)) }, onClick = { to = node; toMenu = false })
            } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TeamRouteAction.entries.forEach { item ->
                FilterChip(selected = action == item, onClick = { action = item }, label = { Text(teamRouteActionLabel(item)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Button(onClick = {
            val source = from; val target = to
            if (source != null && target != null) onChange((routes + TeamRoute(source, target, action)).distinct())
        }, enabled = from in nodes && to in nodes && from != to, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.team_flow_add)) }
        routes.forEachIndexed { index, route ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("${label(route.from)} → ${label(route.to)} · ${teamRouteActionLabel(route.action)}", modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(routes.filterIndexed { i, _ -> i != index }) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.team_flow_remove)) }
            }
        }
    }
}

@Composable
internal fun teamRouteActionLabel(action: TeamRouteAction) = stringResource(when (action) {
    TeamRouteAction.EXECUTE -> R.string.team_flow_execute
    TeamRouteAction.REVIEW -> R.string.team_flow_review
    TeamRouteAction.REJECT -> R.string.team_flow_reject
})
