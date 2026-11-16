package com.aharou.feature.settings.presentation.component

import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamStageForm
import com.aharou.feature.agent.domain.subagent.MAIN_AGENT_ADMIN
import com.aharou.feature.agent.domain.subagent.TeamFlowGraph
import com.aharou.feature.agent.domain.subagent.TeamFlowNode
import com.aharou.feature.agent.domain.subagent.TeamInterface
import com.aharou.feature.agent.domain.subagent.TeamInterfaceEdgeForm
import com.aharou.feature.agent.domain.subagent.TeamMemberRef
import com.aharou.feature.agent.domain.subagent.TeamRoute
import com.aharou.feature.agent.domain.subagent.TeamRouteAction
import com.aharou.feature.agent.domain.subagent.TeamStage
import com.aharou.feature.agent.domain.subagent.TeamTask

internal fun teamNodeReference(node: TeamFlowNode): String = "${node.stageId}/${node.taskId}"

internal fun removeDeletedTeamNodeReferences(previous: List<AgentTeamStageForm>, updated: List<AgentTeamStageForm>): List<AgentTeamStageForm> {
    val oldReferences = previous.flatMap { stage -> stage.tasks.map { teamNodeReference(TeamFlowNode(stage.id, it.id)) } }.toSet()
    val newReferences = updated.flatMap { stage -> stage.tasks.map { teamNodeReference(TeamFlowNode(stage.id, it.id)) } }.toSet()
    val removed = oldReferences - newReferences
    return updated.map { stage -> stage.copy(tasks = stage.tasks.map { task ->
        task.copy(interfaces = task.interfaces.filterNot { it.with in removed })
    }) }
}

internal fun teamDefaultRoutes(stages: List<AgentTeamStageForm>): List<TeamRoute> =
    stages.filter { stage -> stage.tasks.any { it.prompt.isNotBlank() } }.zipWithNext().flatMap { (from, to) ->
        from.tasks.filter { it.prompt.isNotBlank() }.flatMap { source ->
            to.tasks.filter { it.prompt.isNotBlank() }.map { target ->
                TeamRoute(TeamFlowNode(from.id, source.id), TeamFlowNode(to.id, target.id),
                    if (to.isReview) TeamRouteAction.REVIEW else TeamRouteAction.EXECUTE)
            }
        }
    }

internal fun editTeamCollaboration(stages: List<AgentTeamStageForm>, sources: Set<TeamFlowNode>,
    targets: Set<TeamFlowNode>, bidirectional: Boolean, about: String, remove: Boolean = false): List<AgentTeamStageForm> {
    val nodes = stages.flatMap { s -> s.tasks.map { TeamFlowNode(s.id, it.id) } }.toSet()
    require(sources.isNotEmpty() && targets.isNotEmpty() && nodes.containsAll(sources + targets))
    val pairs = sources.flatMap { from -> targets.filter { it != from }.map { from to it } }.toMutableSet()
    if (bidirectional) pairs += pairs.map { (from, to) -> to to from }
    return stages.map { stage ->
        val tasks = stage.tasks.map { task ->
            val from = TeamFlowNode(stage.id, task.id)
            val affected = pairs.filter { it.first == from }.map { it.second }.toSet()
            val kept = task.interfaces.filterNot { edge -> affected.any { edge.with == teamNodeReference(it) } }
            task.copy(interfaces = kept + if (remove) emptyList() else affected.map { TeamInterface(teamNodeReference(it), about) })
        }
        stage.copy(tasks = tasks, interfaces = tasks.flatMap { task -> task.interfaces.map { TeamInterfaceEdgeForm(task.agent, it.with, it.about) } })
    }
}

internal fun editTeamRoutes(stages: List<AgentTeamStageForm>, routes: List<TeamRoute>,
    sources: Set<TeamFlowNode>, targets: Set<TeamFlowNode>, action: TeamRouteAction,
    remove: Boolean = false): List<TeamRoute>? {
    val nodes = stages.flatMap { stage -> stage.tasks.filter { it.prompt.isNotBlank() }.map { TeamFlowNode(stage.id, it.id) } }.toSet()
    if (sources.isEmpty() || targets.isEmpty() || !nodes.containsAll(sources + targets) || sources.any { it in targets }) return null
    val base = routes.ifEmpty { teamDefaultRoutes(stages) }
    val kept = base.filterNot { it.from in sources && it.to in targets }
    val updated = (kept + if (remove) emptyList() else sources.flatMap { from -> targets.map { TeamRoute(from, it, action) } }).distinct()
    val team = AgentTeam("", "", stages.map { stage -> TeamStage(stage.title,
        stage.tasks.filter { it.prompt.isNotBlank() }.map { TeamTask(agent = it.agent.takeIf(String::isNotBlank), prompt = it.prompt, id = it.id) },
        id = stage.id, isReview = stage.isReview, condition = stage.condition) }, "", admins = listOf(MAIN_AGENT_ADMIN),
        members = stages.flatMap { it.tasks }.map { it.agent }.filter(String::isNotBlank).distinct().map { TeamMemberRef(it) }, routes = updated)
    return updated.takeIf { TeamFlowGraph.validate(team) == null }
}
