package com.aharou.feature.agent.domain.subagent

import org.junit.Assert.*
import org.junit.Test

class TeamFlowGraphTest {
    private fun node(s: String) = TeamFlowNode(s, "t")
    private fun team(routes: List<TeamRoute>, admins: List<String> = listOf("worker")) = AgentTeam(
        "test", "", listOf("a", "b", "c").map { TeamStage(it, listOf(TeamTask(agent = "worker", prompt = "p", id = "t")), id = it) },
        "", admins = admins, members = listOf(TeamMemberRef("worker")), routes = routes)
    @Test fun forwardCyclesAndUnknownEndpointsAreRejected() {
        assertNotNull(TeamFlowGraph.validate(team(listOf(TeamRoute(node("a"), node("b")), TeamRoute(node("b"), node("a"))))))
        assertNotNull(TeamFlowGraph.validate(team(listOf(TeamRoute(node("a"), node("missing"))))))
    }
    @Test fun exactlyOneAdministratorIsRequired() {
        assertNotNull(TeamFlowGraph.validate(team(emptyList(), emptyList())))
        assertNotNull(TeamFlowGraph.validate(team(emptyList(), listOf("worker", "worker"))))
    }
    @Test fun joinsWaitForAllPredecessorsAndUnconnectedNodesAreNotAwakened() {
        val t = team(listOf(TeamRoute(node("a"), node("c")), TeamRoute(node("b"), node("c"))))
        assertEquals(setOf(node("a"), node("b")), TeamFlowGraph.ready(t, emptySet()).toSet())
        assertTrue(TeamFlowGraph.ready(t, setOf(node("a"))).none { it == node("c") })
        assertEquals(listOf(node("c")), TeamFlowGraph.ready(t, setOf(node("a"), node("b"))))
    }
    @Test fun rejectionMustPointToAnAncestorAndIsBounded() {
        val forward = TeamRoute(node("a"), node("b"), TeamRouteAction.REVIEW)
        assertNull(TeamFlowGraph.validate(team(listOf(forward, TeamRoute(node("b"), node("a"), TeamRouteAction.REJECT)))))
        assertNotNull(TeamFlowGraph.validate(team(listOf(forward, TeamRoute(node("c"), node("a"), TeamRouteAction.REJECT)))))
        assertTrue(TeamFlowGraph.canRework(1))
        assertFalse(TeamFlowGraph.canRework(2))
    }
    @Test fun explicitSelectionCannotInventNodesAndJoinNeedsEverySelectedPredecessor() {
        val t = team(listOf(TeamRoute(node("a"), node("c")), TeamRoute(node("b"), node("c"))))
        assertNotNull(TeamFlowGraph.validateHandoff(t, node("a"), TeamHandoff(setOf(node("missing")))))
        assertNotNull(TeamFlowGraph.validateHandoff(t, node("a"), TeamHandoff(emptySet(), true, "")))
        assertNull(TeamFlowGraph.validateHandoff(t, node("a"), TeamHandoff(emptySet(), true, "needs evidence")))
        val chosen = mapOf(node("a") to emptySet<TeamFlowNode>(), node("b") to setOf(node("c")))
        assertTrue(TeamFlowGraph.selectedReady(t, setOf(node("c")), setOf(node("a"), node("b")), chosen).isEmpty())
    }
}
