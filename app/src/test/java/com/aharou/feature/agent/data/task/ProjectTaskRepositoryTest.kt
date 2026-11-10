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
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Real AtomicFile store: every write goes through stage → validate → invalidateDependents, nothing is patched by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
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

    @Test fun ownedCreationPersistsAnEmptyProjectWithoutResolvingThePlanner() = runBlocking {
        val id = java.util.UUID.randomUUID().toString()
        coEvery { workspaces.allocateProjectWorkspace(id, "/source") } returns
            com.aharou.feature.workspace.domain.model.Workspace("owned", "/owned/$id")
        val noModel = ProjectTaskRepository(context, Provider { error("planner must not be resolved") }, Provider { workspaces })
        val result = noModel.createOwnedProject("  Local project  ", "  Support offline work  ", "/source", id)
        assertFalse(result.ruleFallback)
        assertEquals("Local project", result.project.title)
        assertEquals("Support offline work", result.project.purpose)
        assertEquals("", result.project.requirements)
        val reopened = ProjectTaskRepository(context, Provider { error("no supplier configured") }, Provider { workspaces })
            .load(result.project.workspace)
        assertEquals(result.project, reopened.projects.single())
        assertTrue(reopened.tasks.isEmpty())
        io.mockk.coVerify(exactly = 1) { workspaces.allocateProjectWorkspace(id, "/source") }
        io.mockk.coVerify(exactly = 0) { workflow.completeOnce(any(), any(), any()) }
    }

    @Test fun theFirstRequirementPlansOnlyNewWorkAndKeepsThePurpose() = runBlocking {
        val id = java.util.UUID.randomUUID().toString()
        coEvery { workspaces.allocateProjectWorkspace(id, "/source") } returns
            com.aharou.feature.workspace.domain.model.Workspace("owned", "/owned/$id")
        val store = repository()
        val project = store.createOwnedProject("app", "Keep research organized", "/source", id).project
        val result = store.appendRequirements(id, "Add offline search", project.workspace)
        assertEquals("Keep research organized", result.project.purpose)
        assertEquals("Add offline search", result.project.requirements)
        val reloaded = repository().load(project.workspace)
        assertEquals(result.project, reloaded.projects.single())
        assertEquals(listOf("Add offline search"), reloaded.tasks.map { it.requirement })
        io.mockk.coVerify(exactly = 1) { workflow.completeOnce(any(), match {
            it.contains("Project purpose (background only, not executable requirements): Keep research organized") &&
                it.endsWith("Requirements:\nAdd offline search")
        }, any()) }
    }

    @Test fun observedOverviewThenConcreteListAppendsOnlyFiveTasksAndKeepsExecutionContext() = runBlocking {
        val overview = "生成五本小说，每本小说需要：1) 完整的书名、作者名、题材类型；2) 详细的故事大纲（起承转合）；3) 主要人物设定；4) 世界观背景；5) 分章节的详细情节设计（至少20章）；6) 开篇第一章的完整正文内容。"
        val concrete = "请帮我生成五本小说，分别为：\n1. 《星尘纪事》- 硬科幻小说，公元2847年星际殖民时代，边缘星球发现外星遗迹，引发联邦与殖民地战争。\n2. 《青衫客》- 武侠小说，明末江湖，神秘剑客因失传剑谱卷入纷争，在忠义与爱情间抉择。\n3. 《回声小镇》- 悬疑推理小说，退休刑警回到故乡调查连环死亡案，所有案件指向废弃钟楼。\n4. 《时光当铺》- 都市奇幻小说，午夜开门的神秘当铺可用记忆换取时间，女主人公寻找父亲失踪真相。\n5. 《深海王座》- 海洋冒险小说，22世纪深海探险队在马里亚纳海沟发现失落文明，面临企业贪婪与海怪威胁。\n\n每本小说需要包含：书名、作者笔名、题材类型、完整故事大纲（起承转合）、主要人物设定（至少6位）、世界观背景设定、分章大纲（25-30章）、第一章完整正文（不少于3000字）。"
        assertEquals(105, overview.length)
        assertEquals(352, concrete.length)
        val id = java.util.UUID.randomUUID().toString()
        coEvery { workspaces.allocateProjectWorkspace(id, "/source") } returns
            com.aharou.feature.workspace.domain.model.Workspace("owned", "/owned/$id")
        val store = repository()
        val project = store.createOwnedProject("Observed novels", "Writing background", "/source", id).project
        val first = store.appendRequirements(id, overview, project.workspace)
        assertTrue(first.ruleFallback)
        val afterOverview = repository().load(project.workspace)
        assertEquals(overview, afterOverview.projects.single().requirements)
        assertTrue(afterOverview.tasks.isEmpty())
        val second = store.appendRequirements(id, concrete, project.workspace)
        assertTrue(second.ruleFallback)
        val reloaded = repository().load(project.workspace)
        val requirements = overview + "\n\n" + concrete
        assertEquals(requirements, reloaded.projects.single().requirements)
        assertEquals(5, reloaded.tasks.size)
        assertEquals(concrete.lines().filter { it.matches(Regex("^[1-5]\\. .*")) }, reloaded.tasks.map { it.requirement })
        val common = concrete.lineSequence().first() + "\n" + concrete.substringAfter("\n\n")
        assertTrue(reloaded.tasks.all { it.parentId == null && it.background == common })
        for (task in reloaded.tasks) {
            val prompt = ProjectWorkflowPlan.prompt(task, reloaded.tasks, requirements)
            assertTrue(prompt.contains("Project requirements:\n$requirements"))
            assertTrue(prompt.contains("\nBackground:\n$common"))
            assertTrue(prompt.contains("\nRequirement:\n${task.requirement}"))
        }
    }

    @Test fun legacySnapshotsWithoutPurposeStillLoadButEmptyAndWhitespaceRequirementsAreRejected() = runBlocking {
        val old = repository().createProject("legacy", "Keep this requirement", "/legacy-json").project
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(old.workspace.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val disk = File(context.filesDir, "project-tasks/$key.json")
        val legacy = disk.readText().replace(",\"purpose\":\"\"", "")
        disk.writeText(legacy)
        assertEquals("", repository().load(old.workspace).projects.single().purpose)
        assertEquals(old.requirements, repository().load(old.workspace).projects.single().requirements)
        for (requirements in listOf("", "   ")) {
            disk.writeText("""{"projects":[{"id":"bad","workspace":"/legacy-json","title":"bad","requirements":"$requirements"}]}""")
            try { repository().load(old.workspace); fail("invalid empty legacy project accepted") }
            catch (failure: IllegalStateException) { assertEquals("INVALID_PROJECT_STORE", failure.message) }
        }
        disk.writeText("""{"projects":[{"id":"bad","workspace":"/legacy-json","title":"bad","requirements":"   ","purpose":"context","workspaceOwnership":"owned"}]}""")
        try { repository().load(old.workspace); fail("whitespace requirements accepted") }
        catch (failure: IllegalStateException) { assertEquals("INVALID_PROJECT_STORE", failure.message) }
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
        assertEquals("", project.purpose)
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

    @Test fun deletionRemovesResidualRunningMarkersAndRejectsLateWrites() = runBlocking {
        val store = repository()
        val task = project(store, "running").single()
        val running = store.save(task.copy(status = com.aharou.feature.agent.domain.task.ProjectTaskStatus.RUNNING))
        val before = store.load("/w")
        assertTrue(store.deleteProject(requireNotNull(task.projectId), "/w"))
        val after = store.load("/w")
        assertEquals(before.version + 1, after.version)
        assertTrue(after.projects.isEmpty())
        assertTrue(after.tasks.isEmpty())
        assertEquals(after, repository().load("/w"))
        try { store.save(running); fail("late writes must not resurrect a deleted project") }
        catch (failure: IllegalArgumentException) { assertEquals("PROJECT_NOT_FOUND", failure.message) }
        assertEquals(after, repository().load("/w"))
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

    @Test fun emptyRequirementBranchesNeedNoPlannerAndPersistWithoutChangingPriorTasks() = runBlocking {
        val repo = repository()
        repo.createProject("existing", "old work", "branch-w", "p")
        val original = repo.load("branch-w").tasks
        val noModel = ProjectTaskRepository(context, Provider { error("planner must not be resolved") }, Provider { workspaces })
        val branch = noModel.createRequirementBranch("p", "  New branch  ", "branch-w", original.single().id)
        assertEquals("New branch", branch.title)
        assertTrue(branch.isRequirementBranch)
        assertEquals("", branch.requirement)
        assertTrue(branch.sessionIds.isEmpty())
        val reopened = ProjectTaskRepository(context, Provider { error("no model") }, Provider { workspaces }).load("branch-w")
        assertEquals(original, reopened.tasks.filterNot { it.isRequirementBranch })
        assertEquals(branch, reopened.tasks.single { it.isRequirementBranch })
        try { noModel.createRequirementBranch("p", " ", "branch-w"); fail("title required") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun branchRequirementsRetainOriginalTextAndFallbackNeverTouchesOtherBranches() = runBlocking {
        val repo = repository()
        repo.createProject("existing", "old work", "branch-plan-w", "p")
        val prior = repo.load("branch-plan-w").tasks
        val selected = repo.createRequirementBranch("p", "Selected", "branch-plan-w")
        val sibling = repo.createRequirementBranch("p", "Sibling", "branch-plan-w")
        val wording = "  first new work\nsecond new work  "
        assertTrue(repo.appendBranchRequirements(selected.id, wording, "branch-plan-w").ruleFallback)
        val current = repo.load("branch-plan-w")
        assertEquals(wording, current.tasks.single { it.id == selected.id }.requirement)
        assertEquals(listOf(wording), current.tasks.single { it.id == selected.id }.supplements)
        assertEquals("old work", current.projects.single().requirements)
        assertEquals(sibling, current.tasks.single { it.id == sibling.id })
        assertEquals(prior, current.tasks.filter { it.id in prior.map { t -> t.id } })
        val newTasks = current.tasks.filter { !it.isRequirementBranch && it.id !in prior.map { t -> t.id } }
        assertEquals(2, newTasks.size)
        assertTrue(newTasks.all { it.parentId == selected.id })
    }


    @Test fun realBranchPersistenceAndExecutionPromptsNeverMixSiblingRequirements() = runBlocking {
        val repo = repository()
        repo.createProject("Scoped", "ROOT_ONLY", "isolated-w", "p")
        val first = repo.createRequirementBranch("p", "A", "isolated-w")
        val second = repo.createRequirementBranch("p", "B", "isolated-w")
        repo.appendBranchRequirements(first.id, "A_ONLY_JSON_REQUIREMENT", "isolated-w")
        repo.appendBranchRequirements(second.id, "B_ONLY_MARKDOWN_REQUIREMENT", "isolated-w")
        val snapshot = repository().load("isolated-w")
        assertEquals("ROOT_ONLY", snapshot.projects.single().requirements)
        assertEquals("A_ONLY_JSON_REQUIREMENT", snapshot.tasks.single { it.id == first.id }.requirement)
        assertEquals(listOf("A_ONLY_JSON_REQUIREMENT"), snapshot.tasks.single { it.id == first.id }.supplements)
        val a = snapshot.tasks.single { it.parentId == first.id && !it.isRequirementBranch }
        val b = snapshot.tasks.single { it.parentId == second.id && !it.isRequirementBranch }
        val aPrompt = com.aharou.feature.agent.domain.task.ProjectWorkflowPlan.prompt(a, snapshot.tasks, snapshot.projects.single().requirements)
        val bPrompt = com.aharou.feature.agent.domain.task.ProjectWorkflowPlan.prompt(b, snapshot.tasks, snapshot.projects.single().requirements)
        assertTrue(aPrompt.contains("ROOT_ONLY")); assertTrue(aPrompt.contains("A_ONLY_JSON_REQUIREMENT"))
        assertFalse(aPrompt.contains("B_ONLY_MARKDOWN_REQUIREMENT"))
        assertTrue(bPrompt.contains("ROOT_ONLY")); assertTrue(bPrompt.contains("B_ONLY_MARKDOWN_REQUIREMENT"))
        assertFalse(bPrompt.contains("A_ONLY_JSON_REQUIREMENT"))
    }

}
