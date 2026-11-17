package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.subagent.AgentTeamStageForm
import com.aharou.feature.agent.domain.subagent.TeamFlowNode
import com.aharou.feature.agent.domain.subagent.TeamRoute
import com.aharou.feature.agent.domain.subagent.TeamRouteAction

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TeamRelationshipDialog(
    stages: List<AgentTeamStageForm>,
    routes: List<TeamRoute>,
    initialSelection: Set<TeamFlowNode>,
    onDismiss: () -> Unit,
    onCollaboration: (Set<TeamFlowNode>, Set<TeamFlowNode>, Boolean, String, Boolean) -> Unit,
    onRoute: (Set<TeamFlowNode>, Set<TeamFlowNode>, TeamRouteAction, Boolean) -> Boolean
) {
    val nodes = stages.flatMap { stage -> stage.tasks.filter { it.prompt.isNotBlank() }.map { TeamFlowNode(stage.id, it.id) } }
    fun label(node: TeamFlowNode): String {
        val stage = stages.first { it.id == node.stageId }
        val task = stage.tasks.first { it.id == node.taskId }
        return "${stage.title} · ${task.agent.ifBlank { task.department.ifBlank { task.agentQuery } }}"
    }
    val first = initialSelection.firstOrNull()
    val last = initialSelection.drop(1).firstOrNull()
    val existing = stages.firstOrNull { it.id == first?.stageId }?.tasks?.firstOrNull { it.id == first?.taskId }
        ?.interfaces?.firstOrNull { edge -> last != null && edge.with == teamNodeReference(last) }
    val existingReverse = stages.firstOrNull { it.id == last?.stageId }?.tasks?.firstOrNull { it.id == last?.taskId }
        ?.interfaces?.any { edge -> first != null && edge.with == teamNodeReference(first) } == true
    val existingRoute = routes.firstOrNull { it.from == first && it.to == last }
    var sources by remember(initialSelection) { mutableStateOf(initialSelection.take(1).toSet()) }
    var targets by remember(initialSelection) { mutableStateOf(initialSelection.drop(1).toSet()) }
    var collaboration by remember { mutableStateOf(existing != null || existingRoute == null) }
    var bidirectional by remember(initialSelection) { mutableStateOf(existing != null && existingReverse) }
    var about by remember { mutableStateOf(existing?.about.orEmpty()) }
    var action by remember { mutableStateOf(existingRoute?.action ?: TeamRouteAction.EXECUTE) }
    var error by remember { mutableStateOf(false) }
    fun apply(remove: Boolean) {
        if (sources.isEmpty() || targets.isEmpty() || sources.any { it in targets }) { error = true; return }
        if (collaboration) {
            onCollaboration(sources, targets, bidirectional, about, remove)
            onDismiss()
        } else if (onRoute(sources, targets, action, remove)) onDismiss() else error = true
    }
    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.team_relationship_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FilterChip(collaboration, { collaboration = true }, { Text(stringResource(R.string.team_relationship_collaboration)) })
                    FilterChip(!collaboration, { collaboration = false }, { Text(stringResource(R.string.team_relationship_flow)) })
                }
                Text(stringResource(R.string.team_relationship_sources), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    nodes.forEach { node -> FilterChip(node in sources, {
                        if (node in sources) sources = sources - node else {
                            sources = sources + node
                            targets = targets - node
                        }
                    }, { Text(label(node)) }, modifier = Modifier.testTag("team_relation_source_${teamNodeReference(node)}")) }
                }
                Text(stringResource(R.string.team_relationship_targets), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    nodes.forEach { node -> FilterChip(node in targets, {
                        if (node in targets) targets = targets - node else {
                            targets = targets + node
                            sources = sources - node
                        }
                    }, { Text(label(node)) }, modifier = Modifier.testTag("team_relation_target_${teamNodeReference(node)}")) }
                }
                if (collaboration) {
                    FilterChip(bidirectional, { bidirectional = !bidirectional }, { Text(stringResource(R.string.team_relationship_bidirectional)) })
                    AppTextField(value = about, onValueChange = { about = it },
                        label = stringResource(R.string.team_relationship_about), singleLine = false, minLines = 1, maxLines = 3)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        TeamRouteAction.entries.forEach { item -> FilterChip(action == item, { action = item }, { Text(teamRouteActionLabel(item)) }) }
                    }
                }
                TextButton(onClick = { apply(true) }, modifier = Modifier.testTag("team_relation_remove")) {
                    Text(stringResource(R.string.team_relationship_remove), color = MaterialTheme.colorScheme.error)
                }
                if (error) Text(stringResource(R.string.team_relationship_invalid), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { apply(false) }, modifier = Modifier.testTag("team_relation_apply")) {
                Text(stringResource(R.string.team_relationship_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
