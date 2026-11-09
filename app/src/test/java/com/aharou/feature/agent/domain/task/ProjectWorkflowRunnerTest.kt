package com.aharou.feature.agent.domain.task

import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.data.task.ProjectTaskSnapshot
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.subagent.TaskTool
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectWorkflowRunnerTest {
    private val rootId = "project-flow-test-" + java.util.UUID.randomUUID()
    private val stopped = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun task(id: String, deps: List<String> = emptyList()) = ProjectTask(id = id, workspace = "w", projectId = "p",
        title = id, requirement = id, dependencyIds = deps, version = 1, order = if (id == "a") 1 else 2, createdAt = 1)

    private fun fixture(accepted: Boolean, wait: Boolean = false, owner: String = "", members: List<String> = emptyList(),
        rejectAt: Int? = null, waitAt: Int? = null, capacityWait: Boolean = false, fullChecks: Int = 2, holdFirstMember: Boolean = false,
        beforeCommit: ((ProjectTaskSnapshot) -> ProjectTaskSnapshot)? = null,
        afterMemberEnds: ((ProjectTaskSnapshot) -> ProjectTaskSnapshot)? = null,
        commitStatus: ProjectTaskStatus = ProjectTaskStatus.REVIEW,
        seed: (ProjectTask) -> ProjectTask = { it }): Triple<ProjectWorkflowRunner, () -> ProjectTaskSnapshot, ((ProjectTaskSnapshot) -> ProjectTaskSnapshot) -> Unit> {
        val repository = mockk<ProjectTaskRepository>()
        val guard = Any()
        var snapshot = ProjectTaskSnapshot(version = 1, projects = listOf(ProjectPlan(id = "p", workspace = "w", title = "p", requirements = "a then b")),
            tasks = listOf(seed(task("a").copy(owner = owner, members = members)), task("b", listOf("a"))))
        val seeded = snapshot.tasks[0].processSessionIds.size
        coEvery { repository.load("w") } answers { synchronized(guard) { snapshot } }
        coEvery { repository.linkProjectExecution("p", "w", rootId) } returns Unit
        var memberEnded = false
        var lateWrite = afterMemberEnds
        // A write that lands between the runner's read and its write, once member-a has ended.
        fun landLateWrite() { if (memberEnded) lateWrite?.let { change -> lateWrite = null; snapshot = change(snapshot) } }
        var hook = beforeCommit
        // A write that lands just as the lock is taken for the first write that moves a task into [commitStatus].
        fun landBeforeCommit(proposed: List<ProjectTask>) {
            if (proposed.none { t -> t.status == commitStatus && snapshot.tasks.firstOrNull { it.id == t.id }?.status != commitStatus }) return
            hook?.let { change -> hook = null; snapshot = change(snapshot) }
        }
        coEvery { repository.save(any()) } answers {
            val t = firstArg<ProjectTask>()
            synchronized(guard) {
                landBeforeCommit(listOf(t))
                landLateWrite()
                check(snapshot.tasks.firstOrNull { it.id == t.id }?.let { it.version == t.version } != false) { "STALE_TASK_VERSION" }
                snapshot = snapshot.copy(tasks = snapshot.tasks.map { if (it.id == t.id) t else it })
            }
            t
        }
        coEvery { repository.transact("w", any()) } answers {
            val build = secondArg<(ProjectTaskSnapshot) -> List<ProjectTaskChange>>()
            synchronized(guard) {
                landBeforeCommit(build(snapshot).map { it.task }) // Dry run: only to see which status this write commits.
                landLateWrite()
                val changes = build(snapshot)
                snapshot = snapshot.copy(tasks = snapshot.tasks.map { t -> changes.firstOrNull { it.task.id == t.id }?.task ?: t })
                changes.map { it.task }
            }
        }
        val sessions = mockk<SessionUseCase>(relaxed = true)
        val turns = mockk<AgentTurnRunner>()
        coEvery { turns.createSessionEntity("w") } returns ChatSessionEntity(rootId, "p", 1, 1, "w")
        val tasks = mockk<TaskTool>()
        val created = mutableMapOf<String, Int>()
        var firstTaskCreates = 0
        var capacityRaces = if (capacityWait) 3 else 0
        coEvery { tasks.createProjectMember(any(), any(), any(), any()) } answers {
            val args = buildJsonObject {
                put("description", firstArg<String>()); put("prompt", secondArg<String>())
                thirdArg<String?>()?.let { put("agent", it) }
            }
            assertEquals(rootId, arg<com.aharou.feature.agent.domain.model.AgentContext>(3).sessionId)
                val id = args.getValue("description").jsonPrimitive.content
                if (capacityRaces > 0) {
                    capacityRaces--
                    return@answers ToolResult.Error("busy", "MAX_SUBAGENTS_REACHED")
                }
                val index = if (id == "a") ++firstTaskCreates else 1
                val sessionId = "member-$id" + if (index == 1) "" else "-$index"
                if (id == "a") {
                    val prompt = args.getValue("prompt").jsonPrimitive.content
                    val ordinal = Regex("Participant: ([0-9]+) /").find(prompt)!!.groupValues[1].toInt()
                    val roles = (listOf(owner) + members).filter { it.isNotBlank() }.ifEmpty { listOf("") }
                    assertEquals(roles[ordinal - 1].takeIf { it.isNotBlank() }, args["agent"]?.jsonPrimitive?.content)
                    assertEquals(ordinal > 1, prompt.contains("[${ordinal - 1}: "))
                    if (index > 1 && !holdFirstMember) {
                        val previous = synchronized(guard) { snapshot.tasks[0] }
                        assertEquals(ProjectTaskStatus.RUNNING, previous.status)
                        assertEquals(seeded + index - 1, previous.processSessionIds.size)
                        assertTrue(previous.processMembers.values.all { it.outcome == "SUCCEEDED" })
                    }
                }
                created[sessionId] = index
                ToolResult.Success(buildJsonObject { put("id", sessionId) })
        }
        coEvery { tasks.stopProjectMember(any(), any()) } answers {
            assertEquals(rootId, secondArg<com.aharou.feature.agent.domain.model.AgentContext>().sessionId)
            stopped += firstArg<String>()
            ToolResult.Success(buildJsonObject { put("state", "stopped") })
        }
        val events = mockk<SubAgentEventBus>()
        var capacityChecks = 0
        every { events.isFull } answers { capacityWait && capacityChecks++ < fullChecks }
        every { events.terminalEvent(any()) } answers {
            val id = firstArg<String>()
            (if (wait || created[id] == waitAt || (holdFirstMember && id == "member-a")) null else SubAgentEvent(id, rootId, SubAgentEventType.COMPLETED,
                accepted = accepted && created[id] != rejectAt)).also { if (it != null && id == "member-a") memberEnded = true }
        }
        val messages = mockk<MessagePersistenceUseCase>(relaxed = true)
        coEvery { messages.getMessagesOnce(any()) } returns emptyList()
        coEvery { messages.restoreAll(any()) } returns emptyList()
        val runner = ProjectWorkflowRunner(repository, sessions, turns, javax.inject.Provider { tasks }, events, mockk(), messages, ProjectTaskExecutionCoordinator())
        return Triple(runner, { synchronized(guard) { snapshot } }, { change -> synchronized(guard) { snapshot = change(snapshot) } })
    }

    /** What a branch (new dependency) or a supplement (new requirement) does to the running task through Repository.save. */
    private fun ProjectTaskSnapshot.reviseA(change: (ProjectTask) -> ProjectTask): ProjectTaskSnapshot =
        copy(tasks = tasks.map { if (it.id == "a") change(it).copy(requirementsVersion = it.requirementsVersion + 1, status = ProjectTaskStatus.READY) else it })

    @Test fun aBranchSupersedesTheRunningMemberRunsTheBranchThenReturnsToTheTask() = runBlocking {
        val (runner, snapshot, edit) = fixture(true, holdFirstMember = true)
        val job = launch { runner.run("p", "w") }
        withTimeout(3000) { while (snapshot().tasks[0].processSessionIds.isEmpty()) delay(5) }
        edit { store -> store.reviseA { it.copy(dependencyIds = listOf("c")) }.let { revised ->
            revised.copy(tasks = revised.tasks + task("c").copy(parentId = "a", order = 3, status = ProjectTaskStatus.READY)) } }
        withTimeout(5000) { job.join() }
        val tasks = snapshot().tasks.associateBy { it.id }
        assertEquals(setOf("member-a"), stopped)
        assertEquals("SUPERSEDED", tasks.getValue("a").processMembers.getValue("member-a").outcome)
        assertEquals(listOf("member-c"), tasks.getValue("c").processSessionIds)
        assertEquals(listOf("member-a", "member-a-2"), tasks.getValue("a").processSessionIds)
        assertEquals(listOf("member-b"), tasks.getValue("b").processSessionIds)
        assertTrue(tasks.values.all { it.status == ProjectTaskStatus.REVIEW })
        assertEquals(2L, tasks.getValue("a").processRequirementsVersion)
    }

    @Test fun aSupplementCommittedAfterTheLastCheckIsNotOverwrittenByTheReviewSave() = runBlocking {
        val (runner, snapshot) = fixture(true, beforeCommit = { store ->
            store.reviseA { it.copy(requirement = "a with late supplement", supplements = listOf("late")) } })
        runner.run("p", "w")
        val first = snapshot().tasks[0]
        assertEquals("a with late supplement", first.requirement)
        assertEquals(listOf("member-a", "member-a-2"), first.processSessionIds)
        assertEquals(ProjectTaskStatus.REVIEW, first.status)
        assertEquals(2L, first.processRequirementsVersion)
        assertEquals(listOf("member-b"), snapshot().tasks[1].processSessionIds)
    }

    @Test fun aSupplementRecordedWhileAMemberRunsDoesNotFailTheMemberResultSave() = runBlocking {
        val (runner, snapshot) = fixture(true, afterMemberEnds = { store ->
            store.copy(tasks = store.tasks.map { if (it.id == "a") it.copy(supplements = listOf("late"), version = it.version + 1) else it }) })
        runner.run("p", "w")
        val first = snapshot().tasks[0]
        assertEquals(listOf("late"), first.supplements)
        assertEquals(listOf("member-a"), first.processSessionIds)
        assertEquals("SUCCEEDED", first.processMembers.getValue("member-a").outcome)
        assertEquals(ProjectTaskStatus.REVIEW, first.status)
        assertEquals("SUCCEEDED", first.executionReason)
        assertEquals(ProjectTaskStatus.REVIEW, snapshot().tasks[1].status)
    }

    @Test fun aSupplementCommittedBeforeTheRunningMarkRunsTheMemberOnTheNewRequirements() = runBlocking {
        val (runner, snapshot) = fixture(true, commitStatus = ProjectTaskStatus.RUNNING, beforeCommit = { store ->
            store.reviseA { it.copy(requirement = "a v2", version = it.version + 1) } })
        runner.run("p", "w")
        val first = snapshot().tasks[0]
        assertEquals("a v2", first.requirement)
        assertEquals(listOf("member-a"), first.processSessionIds)
        assertEquals(ProjectTaskStatus.REVIEW, first.status)
        assertEquals(2L, first.processRequirementsVersion)
    }

    @Test fun aSupplementCommittedAsAMemberFailsKeepsTheMemberFailureAndBlocksTheTask() = runBlocking {
        val (runner, snapshot) = fixture(false, commitStatus = ProjectTaskStatus.BLOCKED, beforeCommit = { store ->
            store.copy(tasks = store.tasks.map { if (it.id == "a") it.copy(supplements = listOf("late"), version = it.version + 1) else it }) })
        try { runner.run("p", "w"); fail("must stop after rejection") } catch (expected: IllegalStateException) {
            assertEquals("PROJECT_MEMBER_FAILED", expected.message)
        }
        val first = snapshot().tasks[0]
        assertEquals(ProjectTaskStatus.BLOCKED, first.status)
        assertEquals(listOf("late"), first.supplements)
        assertEquals("FAILED", first.executionReason)
    }

    @Test fun aBranchDuringASingleTaskRunRunsTheBranchAndThenTheTask() = runBlocking {
        val (runner, snapshot, edit) = fixture(true, holdFirstMember = true)
        val job = launch { runner.run("p", "w", "a") }
        withTimeout(3000) { while (snapshot().tasks[0].processSessionIds.isEmpty()) delay(5) }
        edit { store -> store.reviseA { it.copy(dependencyIds = listOf("c")) }.let { revised ->
            revised.copy(tasks = revised.tasks + task("c").copy(parentId = "a", order = 3, status = ProjectTaskStatus.READY,
                createdBySessionId = "member-a")) } }
        withTimeout(5000) { job.join() }
        val tasks = snapshot().tasks.associateBy { it.id }
        assertEquals(listOf("member-c"), tasks.getValue("c").processSessionIds)
        assertEquals(listOf("member-a", "member-a-2"), tasks.getValue("a").processSessionIds)
        assertEquals(ProjectTaskStatus.REVIEW, tasks.getValue("a").status)
        assertTrue(tasks.getValue("b").processSessionIds.isEmpty())
    }

    @Test fun onlyMembersWhoseToolFilterKeepsProjectTasksAreToldAboutIt() = runBlocking {
        val definitions = mockk<AgentDefinitionRepository>()
        every { definitions.find("narrow") } returns AgentDefinition(name = "narrow", description = "", allowedTools = listOf("readFile"), prompt = "")
        every { definitions.find("wide") } returns AgentDefinition(name = "wide", description = "", prompt = "")
        every { definitions.find("gone") } returns null
        val runner = ProjectWorkflowRunner(mockk(), mockk(), mockk(), javax.inject.Provider<TaskTool> { mockk() }, mockk(), definitions, mockk(),
            ProjectTaskExecutionCoordinator())
        assertTrue(runner.projectToolsFor(""))
        assertTrue(runner.projectToolsFor("wide"))
        assertFalse(runner.projectToolsFor("narrow"))
        assertFalse(runner.projectToolsFor("gone"))
    }

    @Test fun aSupplementDuringTheRunRerunsTheTaskInsteadOfEndingTheFlow() = runBlocking {
        val (runner, snapshot, edit) = fixture(true, holdFirstMember = true)
        val job = launch { runner.run("p", "w") }
        withTimeout(3000) { while (snapshot().tasks[0].processSessionIds.isEmpty()) delay(5) }
        edit { store -> store.reviseA { it.copy(requirement = "a with supplement", supplements = listOf("supplement")) } }
        withTimeout(5000) { job.join() }
        val first = snapshot().tasks[0]
        assertEquals(listOf("member-a", "member-a-2"), first.processSessionIds)
        assertEquals("SUPERSEDED", first.processMembers.getValue("member-a").outcome)
        assertEquals("SUCCEEDED", first.processMembers.getValue("member-a-2").outcome)
        assertEquals(ProjectTaskStatus.REVIEW, first.status)
        assertEquals(listOf("member-b"), snapshot().tasks[1].processSessionIds)
    }

    @Test fun oneProjectFlowCreatesMemberRecordsNotIndependentRootChats() = runBlocking {
        val (runner, snapshot) = fixture(true)
        runner.run("p", "w")
        val tasks = snapshot().tasks
        assertTrue(tasks.all { it.status == ProjectTaskStatus.REVIEW && it.processRequirementsVersion == 1L })
        assertEquals(listOf("member-a"), tasks[0].processSessionIds)
        assertEquals(listOf("member-b"), tasks[1].processSessionIds)
        assertTrue(tasks.all { it.sessionIds.isEmpty() && it.acceptedRequirementsVersion == null })
        assertTrue(ProjectFlowSessions.owns(rootId))
    }

    @Test fun cancellingTheProjectStopsItsCurrentMemberAndLeavesDownstreamPending() = runBlocking {
        val (runner, snapshot) = fixture(true, wait = true)
        val job = launch { runner.run("p", "w") }
        withTimeout(3000) { while (snapshot().tasks[0].processSessionIds.isEmpty()) delay(5) }
        job.cancelAndJoin()
        assertEquals(setOf("member-a"), stopped)
        assertEquals("STOPPED", snapshot().tasks[0].executionReason)
        assertTrue(snapshot().tasks[1].processSessionIds.isEmpty())
    }

    @Test fun everyRepeatedAndDifferentRoleActuallyRunsBeforeReview() = runBlocking {
        val (runner, snapshot) = fixture(true, owner = "builder", members = listOf("builder", "reviewer"), capacityWait = true)
        runner.run("p", "w")
        val first = snapshot().tasks[0]
        assertEquals(listOf("member-a", "member-a-2", "member-a-3"), first.processSessionIds)
        assertEquals(listOf("builder", "builder", "reviewer"), first.processParticipants)
        assertEquals(listOf(1, 2, 3), first.processSessionIds.map { first.processMembers.getValue(it).ordinal })
        assertTrue(first.processMembers.values.all { it.outcome == "SUCCEEDED" })
        assertEquals(ProjectTaskStatus.REVIEW, first.status)
        assertEquals(ProjectTaskStatus.REVIEW, snapshot().tasks[1].status)
    }

    @Test fun rejectionAfterOneSuccessKeepsBothRecordsAndDoesNotRunRemainingMembers() = runBlocking {
        val (runner, snapshot) = fixture(true, members = listOf("builder", "reviewer", "builder"), rejectAt = 2)
        try { runner.run("p", "w"); fail("must reject") } catch (expected: IllegalStateException) {
            assertEquals("PROJECT_MEMBER_FAILED", expected.message)
        }
        val first = snapshot().tasks[0]
        assertEquals(listOf("member-a", "member-a-2"), first.processSessionIds)
        assertEquals("SUCCEEDED", first.processMembers.getValue("member-a").outcome)
        assertEquals("FAILED", first.processMembers.getValue("member-a-2").outcome)
        assertEquals(ProjectTaskStatus.BLOCKED, first.status)
        assertNull(first.processRequirementsVersion)
        assertTrue(snapshot().tasks[1].processSessionIds.isEmpty())
    }

    @Test fun cancellationAfterOneSuccessStopsOnlyTheRunningMemberAndKeepsHistory() = runBlocking {
        val (runner, snapshot) = fixture(true, members = listOf("builder", "builder", "reviewer"), waitAt = 2)
        val job = launch { runner.run("p", "w") }
        withTimeout(3000) { while (snapshot().tasks[0].processSessionIds.size < 2) delay(5) }
        job.cancelAndJoin()
        val first = snapshot().tasks[0]
        assertEquals(setOf("member-a-2"), stopped)
        assertEquals("SUCCEEDED", first.processMembers.getValue("member-a").outcome)
        assertEquals("STOPPED", first.processMembers.getValue("member-a-2").outcome)
        assertEquals(2, first.processSessionIds.size)
        assertTrue(snapshot().tasks[1].processSessionIds.isEmpty())
    }

    @Test fun timeoutIsRecordedSeparatelyAndStopsTheRunningMember() = runBlocking {
        val (runner, snapshot) = fixture(true, wait = true)
        try { withTimeout(500) { runner.run("p", "w") }; fail("must time out") }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
        assertEquals("TIMEOUT", snapshot().tasks[0].executionReason)
        assertEquals("TIMEOUT", snapshot().tasks[0].processMembers.getValue("member-a").outcome)
        assertEquals(setOf("member-a"), stopped)
        assertTrue(snapshot().tasks[1].processSessionIds.isEmpty())
    }

    @Test fun rejectedMemberCannotDispatchItsDependentOrMarkTheProjectDelivered() = runBlocking {
        val (runner, snapshot) = fixture(false)
        try { runner.run("p", "w"); fail("must stop after rejection") } catch (expected: IllegalStateException) {
            assertEquals("PROJECT_MEMBER_FAILED", expected.message)
        }
        assertEquals(ProjectTaskStatus.BLOCKED, snapshot().tasks[0].status)
        assertNull(snapshot().tasks[0].processRequirementsVersion)
        assertTrue(snapshot().tasks[1].processSessionIds.isEmpty())
        assertEquals(ProjectTaskStatus.DRAFT, snapshot().tasks[1].status)
    }

    @Test fun waitingForAFreeSlotDoesNotCountTowardTheMemberTimeout() = runBlocking {
        val (runner, snapshot) = fixture(true, capacityWait = true, fullChecks = 6)
        runner.memberTimeoutMs = 200
        runner.run("p", "w")
        assertTrue(snapshot().tasks.all { it.status == ProjectTaskStatus.REVIEW })
    }

    @Test fun aMemberRunningPastItsLimitIsStoppedAsTimeout() = runBlocking {
        val (runner, snapshot) = fixture(true, wait = true)
        runner.memberTimeoutMs = 200
        try { runner.run("p", "w"); fail("must time out") } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
        assertEquals("TIMEOUT", snapshot().tasks[0].executionReason)
        assertEquals(setOf("member-a"), stopped)
    }

    @Test fun blockedRetryKeepsSucceededMembersAndRunsOnlyTheRest() = runBlocking {
        val (runner, snapshot) = fixture(true, owner = "builder", members = listOf("reviewer"), seed = { it.copy(status = ProjectTaskStatus.BLOCKED,
            processSessionIds = listOf("old-a"), processMembers = mapOf("old-a" to ProjectTaskMemberRun("builder", 1, 1, "SUCCEEDED"))) })
        runner.run("p", "w")
        val first = snapshot().tasks[0]
        assertEquals(listOf("old-a", "member-a"), first.processSessionIds)
        assertEquals(2, first.processMembers.getValue("member-a").ordinal)
        assertEquals(ProjectTaskStatus.REVIEW, first.status)
    }

    @Test fun blockedRetryRerunsMembersWhoseRequirementsChanged() = runBlocking {
        val (runner, snapshot) = fixture(true, owner = "builder", members = listOf("reviewer"), seed = { it.copy(status = ProjectTaskStatus.BLOCKED,
            processSessionIds = listOf("old-a"), processMembers = mapOf("old-a" to ProjectTaskMemberRun("builder", 1, 0, "SUCCEEDED"))) })
        runner.run("p", "w")
        assertEquals(listOf("old-a", "member-a", "member-a-2"), snapshot().tasks[0].processSessionIds)
    }

    @Test fun rerunningDeliveredWorkFromTheDetailPageStartsEveryMemberAndStopsAtThatTask() = runBlocking {
        val (runner, snapshot) = fixture(true, owner = "builder", seed = { it.copy(status = ProjectTaskStatus.REVIEW, processRequirementsVersion = 1,
            processSessionIds = listOf("old-a"), processMembers = mapOf("old-a" to ProjectTaskMemberRun("builder", 1, 1, "SUCCEEDED"))) })
        runner.run("p", "w", "a")
        assertEquals(listOf("old-a", "member-a"), snapshot().tasks[0].processSessionIds)
        assertEquals(ProjectTaskStatus.REVIEW, snapshot().tasks[0].status)
        assertTrue(snapshot().tasks[1].processSessionIds.isEmpty())
    }

    @Test fun runningOneTaskRequiresItsPredecessorsToBeDelivered() = runBlocking {
        val (runner, snapshot) = fixture(true)
        try { runner.run("p", "w", "b"); fail("must require a delivered predecessor") } catch (expected: IllegalStateException) {
            assertEquals("PROJECT_FLOW_BLOCKED", expected.message)
        }
        assertTrue(snapshot().tasks.all { it.processSessionIds.isEmpty() })
        assertFalse(ProjectFlowSessions.owns(rootId))
    }
}
