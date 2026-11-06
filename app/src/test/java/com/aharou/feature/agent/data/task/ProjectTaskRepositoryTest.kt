package com.aharou.feature.agent.data.task

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aharou.feature.agent.domain.prompt.PromptFileResolver
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectPlanDraft
import com.aharou.feature.agent.domain.task.ProjectPlanResult
import com.aharou.feature.agent.domain.task.ProjectPlanner
import com.aharou.feature.agent.domain.task.ProjectRevision
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskGraph
import com.aharou.feature.agent.domain.task.ProjectTaskMemberRun
import com.aharou.feature.agent.domain.task.ProjectTaskStatus
import com.aharou.feature.agent.domain.task.ProjectWorkflowPlan
import com.aharou.feature.agent.domain.tool.session.MemberTaskRequest
import com.aharou.feature.agent.domain.tool.session.ProjectTaskTool
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real AtomicFile store: every write goes through stage → validate → invalidateDependents, nothing is patched by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProjectTaskRepositoryTest {
    private lateinit var context: Context
    private val workflow = mockk<AgentWorkflow>()
    private val realPlanner by lazy {
        val prompts = mockk<PromptFileResolver>().also { every { it.resolve(any()) } returns "plan as json" }
        val definitions = mockk<AgentDefinitionRepository>().also { every { it.listEnabled() } returns emptyList() }
        ProjectPlanner(prompts, workflow, definitions)
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "project-tasks").deleteRecursively()
        coEvery { workflow.completeOnce(any(), any(), any()) } returns null // Rule split unless a test answers.
    }

    private fun repository(planner: ProjectPlanner = realPlanner) = ProjectTaskRepository(context, Provider { planner })

    private suspend fun project(repository: ProjectTaskRepository, requirements: String): List<ProjectTask> {
        val created = repository.createProject("app", requirements, "/w")
        return repository.load("/w").tasks.filter { it.projectId == created.project.id }.sortedBy { it.order }
    }

    @Test fun childTasksCanBeAddedThroughThirdAndFourthLevelsAndSurviveReload() = runBlocking {
        val store = repository()
        var parent = project(store, "first level").single()
        val projectId = requireNotNull(parent.projectId)
        for (depth in 1..3) {
            val before = store.load("/w").tasks.map { it.id }.toSet()
            store.addTaskByDescription(projectId, "level ${depth + 1}", "/w", parentId = parent.id)
            val reloaded = repository().load("/w").tasks
            val child = reloaded.single { it.id !in before }
            assertEquals(parent.id, child.parentId)
            assertEquals(depth, ProjectTaskGraph.depth(child, reloaded))
            assertNull(ProjectTaskGraph.validate(reloaded))
            parent = child
        }
        assertEquals(4, repository().load("/w").tasks.size)
    }

    @Test fun branchCommitsTheNewTaskAndTheDependencyInOneValidatedWrite() = runBlocking {
        val store = repository()
        val task = project(store, "only task").single()
        val running = store.save(task.copy(status = ProjectTaskStatus.RUNNING, processSessionIds = listOf("member"),
            processMembers = mapOf("member" to ProjectTaskMemberRun("", 1, task.requirementsVersion))))
        val before = store.load("/w").version
        store.transact("/w") { snapshot -> ProjectTaskTool.memberChanges(snapshot.tasks, "member", MemberTaskRequest("branch", "Research", "why")) }
        val reread = repository().load("/w") // A fresh instance reads only what reached the file.
        assertEquals(before + 2, reread.version)
        val child = reread.tasks.single { it.createdBySessionId == "member" }
        val current = reread.tasks.single { it.id == running.id }
        assertEquals(running.id, child.parentId)
        assertEquals(listOf(child.id), current.dependencyIds)
        assertEquals(running.requirementsVersion + 1, current.requirementsVersion)
        assertNull(ProjectTaskGraph.validate(reread.tasks))
    }

    @Test fun aSupplementIsRecordedBeforeTheModelCallAndSurvivesItsFailure() = runBlocking {
        val store = repository()
        val task = project(store, "only task").single()
        var seenByModel: List<String>? = null
        coEvery { workflow.completeOnce(any(), any(), any()) } answers {
            seenByModel = store.snapshot("/w").tasks.single { it.id == task.id }.supplements
            throw IllegalStateException("provider down")
        }
        assertTrue(store.supplement(task.id, "  keep offline mode  ", "/w"))
        assertEquals(listOf("keep offline mode"), seenByModel)
        val saved = repository().load("/w").tasks.single { it.id == task.id }
        assertEquals(listOf("keep offline mode"), saved.supplements)
        assertEquals("only task", saved.requirement)
        assertEquals(task.requirementsVersion + 1, saved.requirementsVersion)
    }

    @Test fun anEditFromARevisionReturnsDeliveredDownstreamWorkToReady() = runBlocking {
        val store = repository()
        val (first, second) = project(store, "first task\nsecond task")
        val dependent = store.save(second.copy(dependencyIds = listOf(first.id)))
        store.save(dependent.copy(status = ProjectTaskStatus.REVIEW, processRequirementsVersion = dependent.requirementsVersion))
        assertTrue(ProjectWorkflowPlan.delivered(store.load("/w").tasks.single { it.id == second.id }))
        coEvery { workflow.completeOnce(any(), any(), any()) } answers {
            val anchor = Regex("Anchor task id: (\\S+)").find(secondArg<String>())!!.groupValues[1]
            """{"operations":[{"op":"edit","taskId":"$anchor","requirement":"first task, offline too"}]}"""
        }
        assertFalse(store.supplement(first.id, "offline too", "/w"))
        val tasks = repository().load("/w").tasks.associateBy { it.id }
        assertEquals("first task, offline too", tasks.getValue(first.id).requirement)
        val downstream = tasks.getValue(second.id)
        assertEquals(ProjectTaskStatus.READY, downstream.status)
        assertEquals("UPSTREAM_CHANGED", downstream.executionReason)
        assertEquals(dependent.requirementsVersion + 1, downstream.requirementsVersion)
        assertFalse(ProjectWorkflowPlan.delivered(downstream))
    }

    @Test fun aRevisionWithOneInvalidChangeSavesNoneOfItsChanges() = runBlocking {
        val planner = mockk<ProjectPlanner>()
        coEvery { planner.members() } returns emptyList()
        coEvery { planner.plan(any(), any(), any(), any(), any()) } answers {
            ProjectPlanResult(ProjectPlanDraft.build(firstArg<ProjectPlan>().copy(requirements = secondArg())), ruleFallback = true)
        }
        coEvery { planner.revise(any(), any(), any(), any(), any()) } answers {
            val anchor = secondArg<ProjectTask>()
            ProjectRevision(edits = mapOf(anchor.id to "changed"), added = listOf(ProjectTask(workspace = anchor.workspace,
                projectId = anchor.projectId, title = "bad", requirement = "bad", dependencyIds = listOf("missing"))))
        }
        val store = repository(planner)
        val task = project(store, "only task").single()
        try { store.supplement(task.id, "s", "/w"); fail("an invalid add must reject the batch") } catch (_: IllegalArgumentException) { }
        val tasks = repository(planner).load("/w").tasks
        assertEquals("only task", tasks.single().requirement)
        assertEquals(listOf("s"), tasks.single().supplements)
    }
}
