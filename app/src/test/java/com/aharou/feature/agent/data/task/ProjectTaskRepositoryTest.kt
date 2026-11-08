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

    private val workspaces = mockk<com.aharou.feature.workspace.data.repository.WorkspaceRepository>()
    private fun repository(planner: ProjectPlanner = realPlanner) = ProjectTaskRepository(context, Provider { planner }, Provider { workspaces })

    @Test fun ownedWorkspaceDirectoryIsStableDiscoverableAndCannotBeRenamedOrReclaimed() = runBlocking {
        val resolver = mockk<com.aharou.feature.agent.domain.container.ContainerProfileResolver>()
        every { resolver.forWorkspace(any()) } returns com.aharou.feature.agent.domain.container.ContainerProfile.BUILTIN_UBUNTU
        val mode = com.aharou.feature.settings.data.repository.ExecutionModeHolder()
        fun workspaceRepository() = com.aharou.feature.workspace.data.repository.WorkspaceRepository(context,
            mode, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), resolver)
        val workspaces = workspaceRepository()
        val source = requireNotNull(workspaces.createWorkspace("source-${java.util.UUID.randomUUID()}"))
        workspaces.selectWorkspaceByPath(source.path)
        val id = java.util.UUID.randomUUID().toString()
        val allocated = workspaces.allocateProjectWorkspace(id, source.path)
        assertEquals("aharou-project-$id", File(allocated.path).name)
        assertEquals(id, File(allocated.path, ".aharou-project-owner").readText())
        assertEquals(source.path, workspaces.currentPath())
        try { workspaces.allocateProjectWorkspace(id, source.path); fail("existing directory claimed") }
        catch (_: IllegalStateException) { }
        assertNull(workspaces.renameWorkspace(allocated.name, "renamed"))
        assertTrue(File(allocated.path).isDirectory)
        val reopened = workspaceRepository()
        reopened.initialize()
        assertTrue(reopened.workspaces.value.any { it.path == allocated.path })
        assertTrue(reopened.activateProjectWorkspace(allocated.path, reopened.currentPath()) { true })
        assertEquals(allocated.path, reopened.currentPath())
        mode.setMode(com.aharou.feature.settings.data.repository.ExecutionMode.REMOTE_SSH)
        try { workspaces.allocateProjectWorkspace(java.util.UUID.randomUUID().toString(), source.path); fail("remote allocation accepted") }
        catch (_: IllegalStateException) { }
    }

    @Test fun ownedProjectsWithTheSameTitleUseDifferentPathsAndSurviveReload() = runBlocking {
        coEvery { workspaces.allocateProjectWorkspace(any(), "/w") } answers {
            com.aharou.feature.workspace.domain.model.Workspace("aharou-project-${firstArg<String>()}", "/owned/aharou-project-${firstArg<String>()}")
        }
        val store = repository()
        val first = store.createOwnedProject("same", "first", "/w", java.util.UUID.randomUUID().toString()).project
        val second = store.createOwnedProject("same", "second", "/w", java.util.UUID.randomUUID().toString()).project
        assertTrue(first.workspace != second.workspace)
        assertEquals("owned", repository().load(first.workspace).projects.single().workspaceOwnership)
        assertEquals(second, repository().load(second.workspace).projects.single())
        try { store.createProject("another", "task", first.workspace); fail("owned workspace accepted another project") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun allocationFailureDoesNotCallThePlannerOrWriteAProject() = runBlocking {
        val planner = mockk<ProjectPlanner>()
        coEvery { workspaces.allocateProjectWorkspace(any(), any()) } throws IllegalStateException("mkdir failed")
        try { repository(planner).createOwnedProject("name", "requirements", "/w", java.util.UUID.randomUUID().toString()); fail("creation succeeded") }
        catch (_: IllegalStateException) { }
        io.mockk.coVerify(exactly = 0) { planner.members() }
        assertTrue(repository().load("/w").projects.isEmpty())
    }

    @Test fun historicalProjectJsonDefaultsToLegacyWithoutMovingItsWorkspace() {
        val project = kotlinx.serialization.json.Json.decodeFromString<ProjectPlan>("""{"id":"old","workspace":"/original","title":"old","requirements":"task"}""")
        assertEquals("legacy_shared", project.workspaceOwnership)
        assertEquals("/original", project.workspace)
    }

    @Test fun explicitLegacyContinuationKeepsOriginalAndReplansWithoutOldAcceptanceOrSessions() = runBlocking {
        val store = repository()
        val original = store.createProject("legacy", "deliver the app", "/legacy").project
        val task = store.load("/legacy").tasks.single()
        val deliverable = store.save(task.copy(acceptance = "review deliverable", evidenceRefs = listOf("old-artifact")))
        store.save(deliverable.copy(status = ProjectTaskStatus.DONE, acceptedRequirementsVersion = deliverable.requirementsVersion,
            sessionIds = listOf("old-execution")))
        store.linkProjectExecution(original.id, "/legacy", "old-root")
        val before = repository().load("/legacy")
        val id = java.util.UUID.randomUUID().toString()
        coEvery { workspaces.allocateProjectWorkspace(id, "/legacy") } returns
            com.aharou.feature.workspace.domain.model.Workspace("aharou-project-$id", "/continued/$id")
        val continuation = store.continueInOwnedWorkspace(original.id, "/legacy", id).project
        assertEquals(before, repository().load("/legacy"))
        assertEquals(original.id, continuation.continuationSourceProjectId)
        assertEquals(original.title, continuation.title)
        assertEquals(original.requirements, continuation.requirements)
        assertTrue(continuation.id != original.id && continuation.workspace != original.workspace)
        assertTrue(continuation.flowSessionIds.isEmpty())
        val fresh = repository().load(continuation.workspace).tasks
        assertTrue(fresh.isNotEmpty())
        assertTrue(fresh.none { next -> before.tasks.any { it.id == next.id } })
        assertTrue(fresh.all { it.status != ProjectTaskStatus.DONE && it.acceptedRequirementsVersion == null &&
            it.sessionIds.isEmpty() && it.processSessionIds.isEmpty() })
    }

    @Test fun aRunningLegacyProjectCannotAllocateAContinuation() = runBlocking {
        val store = repository()
        val original = store.createProject("legacy", "task", "/running").project
        val task = store.load("/running").tasks.single()
        store.save(task.copy(status = ProjectTaskStatus.RUNNING))
        try { store.continueInOwnedWorkspace(original.id, "/running", java.util.UUID.randomUUID().toString()); fail("running source accepted") }
        catch (_: IllegalArgumentException) { }
        io.mockk.coVerify(exactly = 0) { workspaces.allocateProjectWorkspace(any(), any()) }
    }

    private suspend fun project(repository: ProjectTaskRepository, requirements: String): List<ProjectTask> {
        val created = repository.createProject("app", requirements, "/w")
        return repository.load("/w").tasks.filter { it.projectId == created.project.id }.sortedBy { it.order }
    }

    @Test fun deletionRejectsRunningTasksWithoutMutatingTheStore() = runBlocking {
        val store = repository()
        val task = project(store, "running").single()
        store.save(task.copy(status = com.aharou.feature.agent.domain.task.ProjectTaskStatus.RUNNING))
        val before = store.load("/w")
        try { store.deleteProject(requireNotNull(task.projectId), "/w"); fail("running projects must remain") }
        catch (failure: IllegalStateException) { assertEquals("PROJECT_BUSY", failure.message) }
        assertEquals(before, store.load("/w"))
        assertEquals(before, repository().load("/w"))
    }

    @Test fun deletionIsAtomicIdempotentAndKeepsOtherProjectsAndStandaloneTasks() = runBlocking {
        val store = repository()
        val removed = project(store, "remove me").single()
        val retained = project(store, "keep me").single()
        val standalone = store.save(ProjectTask(workspace = "/w", title = "standalone", requirement = "keep"))
        val otherWorkspace = store.createProject("other", "keep", "/other").project
        val before = store.load("/w")
        assertTrue(store.deleteProject(requireNotNull(removed.projectId), "/w"))
        val after = store.load("/w")
        assertEquals(before.version + 1, after.version)
        assertEquals(listOf(retained, standalone), after.tasks)
        assertEquals(before.projects.filterNot { it.id == removed.projectId }, after.projects)
        assertNull(ProjectTaskGraph.validate(after.tasks))
        assertEquals(after, repository().load("/w"))
        assertEquals(listOf(otherWorkspace), repository().load("/other").projects)
        assertFalse(store.deleteProject(requireNotNull(removed.projectId), "/w"))
        assertEquals(after, store.load("/w"))
        try { store.save(removed); fail("late writes must not resurrect a deleted project") }
        catch (failure: IllegalArgumentException) { assertEquals("PROJECT_NOT_FOUND", failure.message) }
        assertEquals(after, repository().load("/w"))
    }

    @Test fun deletionWriteFailureKeepsThePublishedAndPersistedProject() = runBlocking {
        val store = repository()
        val removed = project(store, "remove me").single()
        val before = store.load("/w")
        val root = File(context.filesDir, "project-tasks")
        val backup = File(context.filesDir, "project-tasks-delete-backup")
        assertTrue(root.renameTo(backup))
        root.writeText("block directory creation")
        try {
            try { store.deleteProject(requireNotNull(removed.projectId), "/w"); fail("write failure must propagate") }
            catch (failure: IllegalStateException) { assertEquals("TASK_DIRECTORY_FAILED", failure.message) }
            assertEquals(before, store.snapshots.value["/w"])
        } finally {
            assertTrue(root.delete())
            assertTrue(backup.renameTo(root))
        }
        assertEquals(before, repository().load("/w"))
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
