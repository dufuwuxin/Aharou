package com.aharou.feature.agent.domain.subagent

/** Stable file ids, not member names: the same person can work in several stages. */
data class TeamFlowNode(val stageId: String, val taskId: String)
enum class TeamRouteAction { EXECUTE, REVIEW, REJECT }
data class TeamRoute(val from: TeamFlowNode, val to: TeamFlowNode, val action: TeamRouteAction = TeamRouteAction.EXECUTE)

/** null next is the explicit backward-compatible static-all-outgoing policy. */
data class TeamHandoff(val next: Set<TeamFlowNode>? = null, val hold: Boolean = false, val reason: String = "")

object TeamFlowGraph {
    const val MAX_REWORK_ROUNDS = 2
    fun nodes(team: AgentTeam): List<TeamFlowNode> = team.stages.flatMap { s -> s.tasks.map { TeamFlowNode(s.id, it.id) } }
    fun forward(team: AgentTeam) = team.routes.filter { it.action != TeamRouteAction.REJECT }
    fun predecessors(team: AgentTeam, node: TeamFlowNode): Set<TeamFlowNode> = forward(team).filter { it.to == node }.map { it.from }.toSet()
    fun isReview(team: AgentTeam, node: TeamFlowNode) = team.stages.any { it.id == node.stageId && it.isReview } ||
        team.routes.any { it.to == node && it.action == TeamRouteAction.REVIEW }
    fun canRework(rounds: Int) = rounds < MAX_REWORK_ROUNDS

    fun descendants(team: AgentTeam, roots: Set<TeamFlowNode>): Set<TeamFlowNode> {
        val result = roots.toMutableSet()
        val queue = java.util.ArrayDeque<TeamFlowNode>(); queue.addAll(roots)
        val outgoing = forward(team).groupBy { it.from }
        while (queue.isNotEmpty()) outgoing[queue.removeFirst()].orEmpty().forEach { if (result.add(it.to)) queue.addLast(it.to) }
        return result
    }

    fun ready(team: AgentTeam, completed: Set<TeamFlowNode>): List<TeamFlowNode> {
        val active = forward(team).flatMap { listOf(it.from, it.to) }.toSet()
        return nodes(team).filter { it in active && it !in completed && completed.containsAll(predecessors(team, it)) }
    }

    fun validateHandoff(team: AgentTeam, from: TeamFlowNode, handoff: TeamHandoff): String? {
        if (handoff.hold && handoff.reason.isBlank()) return "HOLD_REASON_REQUIRED"
        if (handoff.hold && !handoff.next.isNullOrEmpty()) return "HOLD_CANNOT_DISPATCH"
        val allowed = forward(team).filter { it.from == from }.map { it.to }.toSet()
        if (handoff.next?.any { it !in allowed } == true) return "HANDOFF_NOT_AN_OUTGOING_ROUTE"
        return null
    }

    fun selectedReady(team: AgentTeam, scheduled: Set<TeamFlowNode>, completed: Set<TeamFlowNode>,
        selections: Map<TeamFlowNode, Set<TeamFlowNode>>): List<TeamFlowNode> =
        nodes(team).filter { target -> target in scheduled && target !in completed &&
            predecessors(team, target).all { it in completed && target in selections[it].orEmpty() } }

    fun validate(team: AgentTeam): String? {
        if (team.admins.size != 1 || team.admins.single().isBlank()) return "ONE_ADMIN_REQUIRED"
        if (team.members.none { it.name == team.admins.single() }) return "ADMIN_NOT_MEMBER"
        if (team.routes.isEmpty()) return null // Explicit legacy serial execution, not a graph.
        val nodes = nodes(team)
        if (team.stages.map { it.id }.distinct().size != team.stages.size || nodes.distinct().size != nodes.size ||
            nodes.any { it.stageId.isBlank() || it.taskId.isBlank() }) return "DUPLICATE_FLOW_NODE"
        if (team.routes.distinct().size != team.routes.size) return "DUPLICATE_ROUTE"
        if (team.routes.any { it.from !in nodes || it.to !in nodes || it.from == it.to }) return "UNKNOWN_FLOW_NODE"
        val forward = forward(team)
        if (forward.isEmpty()) return "FORWARD_ROUTE_REQUIRED"
        if (forward.groupBy { it.to }.any { (_, edges) -> edges.map { it.action }.distinct().size != 1 }) return "MIXED_TARGET_ACTIONS"
        val active = forward.flatMap { listOf(it.from, it.to) }.toSet()
        val completed = mutableSetOf<TeamFlowNode>()
        while (completed.size < active.size) {
            val next = ready(team, completed)
            if (next.isEmpty()) return "FLOW_CYCLE"
            completed.addAll(next)
        }
        for (edge in team.routes.filter { it.action == TeamRouteAction.REJECT }) {
            if (!isReview(team, edge.from) || edge.from !in descendants(team, setOf(edge.to))) return "REJECT_MUST_TARGET_ANCESTOR"
        }
        return null
    }
}
