package com.aharou.feature.settings.presentation

import com.aharou.feature.agent.domain.subagent.AgentTeamStageForm
import com.aharou.feature.agent.domain.subagent.AgentTeamTaskForm
import com.aharou.feature.agent.domain.subagent.TeamFlowGraph
import com.aharou.feature.agent.domain.subagent.TeamFlowNode
import com.aharou.feature.agent.domain.subagent.TeamInterface
import com.aharou.feature.agent.domain.subagent.TeamRouteAction
import com.aharou.feature.settings.presentation.component.editTeamCollaboration
import com.aharou.feature.settings.presentation.component.editTeamRoutes
import com.aharou.feature.settings.presentation.component.removeDeletedTeamNodeReferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamRelationshipEditsTest {
    private val stages = listOf(
        AgentTeamStageForm(title = "Build", id = "build", tasks = listOf(
            AgentTeamTaskForm(agent = "builder", prompt = "implement", id = "builder-task"),
            AgentTeamTaskForm(agent = "tester", prompt = "test", id = "tester-task")
        )),
        AgentTeamStageForm(title = "Review", id = "review", isReview = true, tasks = listOf(
            AgentTeamTaskForm(agent = "reviewer", prompt = "review", id = "review-task")
        ))
    )

    @Test fun collaborationSupportsManyToManyBidirectionalEdgesWithoutAddingExecutionRoutes() {
        val sources = setOf(TeamFlowNode("build", "builder-task"), TeamFlowNode("build", "tester-task"))
        val target = setOf(TeamFlowNode("review", "review-task"))
        val edited = editTeamCollaboration(stages, sources, target, true, "API contract")
        val edges = edited.flatMap { it.tasks }.flatMap { it.interfaces }
        assertEquals(4, edges.size)
        assertTrue(edges.all { it.about == "API contract" })
        assertTrue(edited.all { stage -> stage.tasks.all { it.id in setOf("builder-task", "tester-task", "review-task") } })
        assertFalse(edited.any { stage -> stage.tasks.any { it.prompt.contains("route") } })
    }

    @Test fun editingOneRelationPreservesExistingMemberNameReferences() {
        val legacy = stages.map { stage -> stage.copy(tasks = stage.tasks.map { task ->
            if (task.id == "builder-task") task.copy(interfaces = listOf(TeamInterface("reviewer", "legacy edge"))) else task
        }) }
        val edited = editTeamCollaboration(legacy,
            setOf(TeamFlowNode("build", "tester-task")),
            setOf(TeamFlowNode("review", "review-task")), false, "new edge")
        val original = edited.first().tasks.first { it.id == "builder-task" }.interfaces.single()
        assertEquals("reviewer", original.with)
        assertEquals("legacy edge", original.about)
    }

    @Test fun removingAStableTaskDropsOnlyItsStableReferenceAndPreservesLegacyTargets() {
        val previous = listOf(AgentTeamStageForm(title = "Build", id = "stage", tasks = listOf(
            AgentTeamTaskForm(agent = "builder", prompt = "build", id = "kept", interfaces = listOf(
                TeamInterface("stage/removed", "deleted node"),
                TeamInterface("reviewer", "legacy member"),
                TeamInterface("Old stage", "legacy stage")
            )),
            AgentTeamTaskForm(agent = "tester", prompt = "test", id = "removed")
        )))
        val updated = listOf(previous.single().copy(tasks = previous.single().tasks.take(1)))
        val kept = removeDeletedTeamNodeReferences(previous, updated).single().tasks.single().interfaces
        assertEquals(listOf(TeamInterface("reviewer", "legacy member"), TeamInterface("Old stage", "legacy stage")), kept)
    }

    @Test fun executionRelationsUseValidatedRoutesAndRejectCycles() {
        val first = setOf(TeamFlowNode("build", "builder-task"), TeamFlowNode("build", "tester-task"))
        val last = setOf(TeamFlowNode("review", "review-task"))
        val forward = requireNotNull(editTeamRoutes(stages, emptyList(), first, last, TeamRouteAction.REVIEW))
        assertEquals(2, forward.size)
        val cycle = editTeamRoutes(stages, forward, last, first, TeamRouteAction.EXECUTE)
        assertNull(cycle)
    }

    @Test fun legacyMemberLevelsDoNotChangeTheRuntimeGraph() {
        val source = stages.map { stage -> stage.copy(level = 4) }
        val routes = requireNotNull(editTeamRoutes(source, emptyList(),
            setOf(TeamFlowNode("build", "builder-task"), TeamFlowNode("build", "tester-task")),
            setOf(TeamFlowNode("review", "review-task")), TeamRouteAction.EXECUTE))
        val form = source
        assertEquals(4, form.first().level)
        assertEquals(4, form.last().level)
        assertTrue(routes.isNotEmpty())
        val mainTeam = com.aharou.feature.agent.domain.subagent.AgentTeam("team", "", form.map { stage ->
            com.aharou.feature.agent.domain.subagent.TeamStage(stage.title,
                stage.tasks.map { com.aharou.feature.agent.domain.subagent.TeamTask(agent = it.agent, prompt = it.prompt, id = it.id) },
                id = stage.id, isReview = stage.isReview)
        }, "", admins = listOf(com.aharou.feature.agent.domain.subagent.MAIN_AGENT_ADMIN), routes = routes,
            members = listOf("builder", "tester", "reviewer").map { com.aharou.feature.agent.domain.subagent.TeamMemberRef(it) })
        assertNull(TeamFlowGraph.validate(mainTeam))
    }
    @Test fun feedbackReviewCanRunBackwardsWithoutConvertingForwardReviewOrLosingMetadata() {
        val before = stages
        val from = setOf(TeamFlowNode("review", "review-task"))
        val to = setOf(TeamFlowNode("build", "builder-task"))
        val routes = requireNotNull(editTeamRoutes(stages, emptyList(), from, to, TeamRouteAction.REVIEW, feedback = true))
        assertTrue(routes.single { it.feedback }.from in from)
        assertTrue(routes.single { it.feedback }.to in to)
        assertEquals(before, stages)
        val kept = requireNotNull(editTeamRoutes(stages, routes, from, to, TeamRouteAction.REVIEW, remove = true, feedback = true))
        assertTrue(kept.none { it.feedback })
        assertTrue(kept.isNotEmpty())
    }

}
