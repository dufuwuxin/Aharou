package com.aharou.feature.agent.domain.task

import com.aharou.feature.agent.domain.prompt.PromptFileResolver
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectPlannerTest {
    private val workflow = mockk<AgentWorkflow>()
    private val prompts = mockk<PromptFileResolver>().also { every { it.resolve(any()) } returns "<!-- note -->\nplan as json" }
    private val planner = ProjectPlanner(prompts, workflow, mockk())
    private val project = ProjectPlan(id = "p", workspace = "w", title = "app", requirements = "first line\nsecond line", createdAt = 10)

    private fun answer(text: String?) { coEvery { workflow.completeOnce(any(), any(), any()) } returns text }
    private fun task(id: String, parent: String? = null) = ProjectTask(id = id, workspace = "w", projectId = "p", title = id,
        requirement = id, parentId = parent, version = 1, order = 1, createdAt = 1)

    @Test fun planningAndRevisionReceivePurposeAsBackgroundWithoutTurningItIntoWork() = runBlocking {
        val meaningful = project.copy(purpose = "Research archive", requirements = "")
        answer(null)
        val result = planner.plan(meaningful, "Add offline search", emptyList(), emptyList())
        assertEquals(listOf("Add offline search"), result.tasks.map { it.requirement })
        io.mockk.coVerify(exactly = 1) { workflow.completeOnce(any(), match {
            it.contains("Project purpose (background only, not executable requirements): Research archive") &&
                it.endsWith("Requirements:\nAdd offline search")
        }, any()) }
        planner.revise(meaningful, task("t"), listOf(task("t")), "Keep search offline", emptyList())
        io.mockk.coVerify(exactly = 1) { workflow.completeOnce(any(), match {
            it.contains("Project purpose (background only, not executable requirements): Research archive") &&
                it.endsWith("User supplement:\nKeep search offline")
        }, any()) }
    }

    @Test fun invalidJsonOrNoModelFallsBackToLineRules() = runBlocking {
        for (raw in listOf("not json at all", "{\"tasks\": \"none\"}", "{\"tasks\":[{\"title\":\"\"}]}", null)) {
            answer(raw)
            val result = planner.plan(project, project.requirements, emptyList(), listOf("builder"))
            assertTrue(raw.toString(), result.ruleFallback)
            assertEquals(listOf("first line", "second line"), result.tasks.map { it.title })
        }
    }

    @Test fun parentKeysAndDependenciesMapToTaskIdsAndMembersStayInTheEnabledList() = runBlocking {
        answer("""Here: {"tasks":[
            {"key":"r","title":"Research","requirement":"read code","owner":"builder","members":["reviewer","ghost"]},
            {"key":"c","title":"Change","requirement":"edit","parentKey":"r","dependsOn":["r","missing","c"],"owner":"ghost"},
            {"key":"x","title":"Existing child","parentKey":"old","dependsOn":["old"]}]} thanks""")
        val result = planner.plan(project, "text", listOf(task("old")), listOf("builder", "reviewer"))
        assertFalse(result.ruleFallback)
        val (research, change, child) = result.tasks
        assertNull(research.parentId)
        assertEquals("builder", research.owner)
        assertEquals(listOf("reviewer"), research.members)
        assertEquals(research.id, change.parentId)
        assertEquals(listOf(research.id), change.dependencyIds)
        assertEquals("", change.owner)
        assertEquals("old", child.parentId)
        assertEquals(listOf("old"), child.dependencyIds)
        assertEquals("Existing child", child.requirement)
        assertTrue(result.tasks.all { it.projectId == "p" && it.workspace == "w" && it.status == ProjectTaskStatus.DRAFT })
    }

    @Test fun answersAreCutToTheTaskAndDepthLimits() = runBlocking {
        answer("{\"tasks\":[" + (1..40).joinToString(",") { "{\"key\":\"k$it\",\"title\":\"t$it\"}" } + "]}")
        assertEquals(ProjectPlanner.MAX_TASKS, planner.plan(project, "x", emptyList(), emptyList()).tasks.size)
        answer("{\"tasks\":[" + (0..6).joinToString(",") {
            "{\"key\":\"d$it\",\"title\":\"level $it\"" + (if (it > 0) ",\"parentKey\":\"d${it - 1}\"" else "") + "}"
        } + "]}")
        val chain = planner.plan(project, "x", emptyList(), emptyList()).tasks
        assertEquals((0..ProjectTaskGraph.MAX_TREE_DEPTH).map { "level $it" }, chain.map { it.title })
    }

    @Test fun aFixedParentHoldsEveryNewTopLevelTaskInBothModelAndRulePaths() = runBlocking {
        val existing = listOf(task("old"), task("other"))
        answer("""{"tasks":[{"key":"n1","title":"First"},{"key":"n2","title":"Inner","parentKey":"n1"},
            {"key":"n3","title":"Elsewhere","parentKey":"other","dependsOn":["other"]}]}""")
        val (first, inner, elsewhere) = planner.plan(project, "x", existing, emptyList(), parentId = "old").tasks
        assertEquals("old", first.parentId)
        assertEquals(first.id, inner.parentId)
        assertEquals("old", elsewhere.parentId)
        assertEquals(listOf("other"), elsewhere.dependencyIds)
        answer(null)
        val rules = planner.plan(project, "one\ntwo", existing, emptyList(), parentId = "old")
        assertTrue(rules.ruleFallback)
        assertTrue(rules.tasks.all { it.parentId == "old" })
        val chain = (0..ProjectTaskGraph.MAX_TREE_DEPTH).map { task("c$it", if (it == 0) null else "c${it - 1}") }
        assertTrue(planner.plan(project, "one", chain, emptyList(), parentId = "c${ProjectTaskGraph.MAX_TREE_DEPTH}").tasks.isEmpty())
    }

    @Test fun aDependencyCycleIsRejectedAndFallsBack() = runBlocking {
        answer("""{"tasks":[{"key":"a","title":"a","dependsOn":["b"]},{"key":"b","title":"b","dependsOn":["a"]}]}""")
        assertTrue(planner.plan(project, project.requirements, emptyList(), emptyList()).ruleFallback)
    }

    @Test fun revisionKeepsOperationsInsideTheAnchorBranch() = runBlocking {
        val tasks = listOf(task("anchor"), task("child", "anchor"), task("other"))
        answer("""{"operations":[
            {"op":"edit","taskId":"child","requirement":"child with supplement"},
            {"op":"edit","taskId":"other","requirement":"not in branch"},
            {"op":"rerun","taskId":"anchor"},{"op":"rerun","taskId":"other"},
            {"op":"add","key":"n","title":"New work","requirement":"from supplement"}]}""")
        val revision = planner.revise(project, tasks[0], tasks, "supplement", emptyList())
        assertFalse(revision.ruleFallback)
        assertEquals(mapOf("child" to "child with supplement"), revision.edits)
        assertEquals(setOf("anchor"), revision.reruns)
        assertEquals("anchor", revision.added.single().parentId)
        assertEquals(ProjectTaskStatus.READY, revision.added.single().status)
        val changes = revision.touched.map { id -> revision.change(tasks.first { it.id == id }) }
        assertEquals("child with supplement", changes.first { it.task.id == "child" }.task.requirement)
        assertTrue(changes.first { it.task.id == "anchor" }.force)
    }

    @Test fun revisionWithoutAModelRerunsTheAnchorWithoutCopyingTheSupplement() = runBlocking {
        val anchor = task("anchor")
        for (raw in listOf(null, "{\"operations\":[]}", "{\"operations\":[{\"op\":\"edit\",\"taskId\":\"elsewhere\",\"requirement\":\"x\"}]}")) {
            answer(raw)
            val revision = planner.revise(project, anchor, listOf(anchor), "also handle offline", emptyList())
            assertTrue(raw.toString(), revision.ruleFallback)
            assertEquals(listOf("anchor"), revision.touched)
            assertEquals("anchor", revision.change(anchor).task.requirement)
            assertTrue(revision.change(anchor).force)
            val prompt = ProjectWorkflowPlan.prompt(revision.change(anchor.copy(supplements = listOf("also handle offline"))).task, listOf(anchor), "r")
            assertEquals(1, Regex.fromLiteral("also handle offline").findAll(prompt).count())
        }
    }

    @Test fun anUnknownParentKeyFallsBackToTheDefaultParent() = runBlocking {
        val existing = listOf(task("old"))
        answer("""{"tasks":[{"key":"n","title":"Orphan","parentKey":"no-such-task-or-key"}]}""")
        assertNull(planner.plan(project, "x", existing, emptyList()).tasks.single().parentId)
        assertEquals("old", planner.plan(project, "x", existing, emptyList(), parentId = "old").tasks.single().parentId)
        answer("""{"operations":[{"op":"add","key":"n","title":"Orphan","parentKey":"no-such-task-or-key"}]}""")
        assertEquals("old", planner.revise(project, existing[0], existing, "s", emptyList()).added.single().parentId)
    }

    @Test fun aForcedRerunInvalidatesDeliveredWorkWithoutAFieldChange() {
        val done = task("anchor").copy(status = ProjectTaskStatus.DONE, acceptance = "ok", evidenceRefs = listOf("e"), acceptedRequirementsVersion = 1)
        val unchanged = ProjectTaskGraph.revise(done, done, 2)
        assertEquals(1L, unchanged.requirementsVersion)
        assertEquals(ProjectTaskStatus.DONE, unchanged.status)
        val rerun = ProjectTaskGraph.revise(done, done, 2, force = true)
        assertEquals(2L, rerun.requirementsVersion)
        assertEquals(ProjectTaskStatus.READY, rerun.status)
        assertNull(rerun.acceptedRequirementsVersion)
        assertEquals(1L, ProjectTaskGraph.revise(done, done.copy(supplements = listOf("note")), 2).requirementsVersion)
    }
}
