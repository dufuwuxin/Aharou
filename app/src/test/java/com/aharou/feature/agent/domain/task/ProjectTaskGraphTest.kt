package com.aharou.feature.agent.domain.task

import org.junit.Assert.*
import org.junit.Test

class ProjectTaskGraphTest {
    private fun task(id: String, parent: String? = null, workspace: String = "w") =
        ProjectTask(id = id, workspace = workspace, title = id, requirement = "deliver", parentId = parent,
            order = 1_700_000_000_000L, createdAt = 1_700_000_000_000L)
    @Test fun crossProjectEdgesAreRejectedAndOldNullProjectsRemainValid() {
        val a = task("a").copy(projectId = "p1")
        val b = task("b", "a").copy(projectId = "p2")
        assertNotNull(ProjectTaskGraph.validate(listOf(a, b)))
        assertNotNull(ProjectTaskGraph.validate(listOf(a, b.copy(parentId = null, dependencyIds = listOf("a")))))
        assertNull(ProjectTaskGraph.validate(listOf(task("a"), task("b", "a"))))
        val legacy = kotlinx.serialization.json.Json.decodeFromString(ProjectTask.serializer(),
            """{"id":"old","workspace":"w","title":"old","requirement":"unchanged"}""")
        assertNull(legacy.projectId)
    }
    @Test fun missingParentCrossWorkspaceAndCyclesAreRejected() {
        assertNotNull(ProjectTaskGraph.validate(listOf(task("a", "missing"))))
        assertNotNull(ProjectTaskGraph.validate(listOf(task("a"), task("b", "a", "other"))))
        assertNotNull(ProjectTaskGraph.validate(listOf(task("a", "b"), task("b", "a"))))
    }
    @Test fun executionSessionsCannotBelongToTwoTasks() {
        assertNotNull(ProjectTaskGraph.validate(listOf(task("a").copy(sessionIds = listOf("s")), task("b").copy(sessionIds = listOf("s")))))
    }
    @Test fun requirementsChangeInvalidatesPriorAcceptance() {
        val old = task("a").copy(status = ProjectTaskStatus.DONE, evidenceRefs = listOf("file:1"), acceptedRequirementsVersion = 1)
        val changed = ProjectTaskGraph.revise(old, old.copy(requirement = "different"), 2)
        assertEquals(2L, changed.requirementsVersion)
        assertNull(changed.acceptedRequirementsVersion)
        assertEquals(ProjectTaskStatus.READY, changed.status)
        assertNotNull(ProjectTaskGraph.validate(listOf(changed.copy(status = ProjectTaskStatus.DONE))))
    }
    @Test fun treeOrderIsStableAndLongChainsAreIterative() {
        val tasks = (0..1000).map { task("n$it", if (it == 0) null else "n${it-1}").copy(order = it.toLong()) }
        assertNull(ProjectTaskGraph.validate(tasks))
        assertEquals(tasks.map { it.id }, ProjectTaskGraph.tree(tasks.reversed()).map { it.task.id })
    }
    @Test fun jsonRoundtripPreservesTaskSessionAndAcceptanceVersions() {
        val original = task("a").copy(sessionIds = listOf("s1", "s2"), evidenceRefs = listOf("src/file:8"),
            acceptance = "tested", acceptedRequirementsVersion = 1, status = ProjectTaskStatus.DONE, version = 7)
        val repositoryJson = kotlinx.serialization.json.Json { encodeDefaults = true }
        val encoded = repositoryJson.encodeToString(ProjectTask.serializer(), original)
        assertTrue(encoded.contains("\"order\":${original.order}"))
        assertTrue(encoded.contains("\"createdAt\":${original.createdAt}"))
        assertEquals(original, repositoryJson.decodeFromString(ProjectTask.serializer(), encoded))
    }
    @Test fun coldRecoveryInterruptsOnlyPersistedRunningAndPreservesHistory() {
        val running = task("a").copy(status = ProjectTaskStatus.RUNNING, sessionIds = listOf("old-session"))
        val recovered = ProjectTaskGraph.recoverInterrupted(running)
        assertEquals(ProjectTaskStatus.BLOCKED, recovered.status)
        assertEquals(listOf("old-session"), recovered.sessionIds)
        assertTrue(recovered.executionReason.contains("INTERRUPTED"))
        assertEquals(recovered, ProjectTaskGraph.recoverInterrupted(recovered))
        assertEquals(task("b"), ProjectTaskGraph.recoverInterrupted(task("b")))
    }
    @Test fun optionalContextReadErrorReportsUnavailableAndGhostNeverReadsTaskStore() {
        var reads = 0
        val unavailable = ProjectTaskGraph.currentContextTask("s", "w", false) { reads++; error("corrupt") }
        assertTrue(unavailable.unavailable)
        assertNull(unavailable.task)
        val ghost = ProjectTaskGraph.currentContextTask("s", "w", true) { reads++; error("must not read") }
        assertFalse(ghost.unavailable)
        assertNull(ghost.task)
        assertEquals(1, reads)
    }
    @Test fun dependenciesRejectMissingCrossWorkspaceAndCyclesAndWaitForAcceptedTasks() {
        assertNotNull(ProjectTaskGraph.validate(listOf(task("a").copy(dependencyIds = listOf("missing")))))
        assertNotNull(ProjectTaskGraph.validate(listOf(task("a").copy(dependencyIds = listOf("b")), task("b", workspace = "other"))))
        assertNotNull(ProjectTaskGraph.validate(listOf(task("a").copy(dependencyIds = listOf("b")), task("b").copy(dependencyIds = listOf("a")))))
        val a = task("a").copy(dependencyIds = listOf("b")); val b = task("b")
        assertFalse(ProjectTaskGraph.dependenciesReady(a, listOf(a, b)))
        val accepted = b.copy(status = ProjectTaskStatus.DONE, acceptance = "ok", evidenceRefs = listOf("file:1"), acceptedRequirementsVersion = 1)
        assertTrue(ProjectTaskGraph.dependenciesReady(a, listOf(a, accepted)))
        assertFalse(ProjectTaskGraph.dependenciesReady(a, listOf(a, accepted.copy(evidenceRefs = emptyList()))))
    }
    @Test fun completedDependentCannotRemainAcceptedAfterItsPrerequisiteIsInvalidated() {
        val upstream = task("a").copy(status = ProjectTaskStatus.READY)
        val downstream = task("b").copy(dependencyIds = listOf("a"), status = ProjectTaskStatus.DONE,
            acceptance = "verified", evidenceRefs = listOf("report:1"), acceptedRequirementsVersion = 1)
        assertNotNull(ProjectTaskGraph.validate(listOf(upstream, downstream)))
    }
    @Test fun upstreamChangesInvalidateTransitiveAcceptanceAndQueuedExecutionStamps() {
        fun accepted(id: String, deps: List<String> = emptyList()) = task(id).copy(
            dependencyIds = deps, status = ProjectTaskStatus.DONE, acceptance = "verified",
            evidenceRefs = listOf("report:1"), acceptedRequirementsVersion = 1, version = 1)
        val upstream = accepted("a").copy(requirementsVersion = 2, acceptedRequirementsVersion = null,
            status = ProjectTaskStatus.READY, version = 10)
        val dependent = accepted("b", listOf("a")).copy(sessionIds = listOf("queued"), executionRequirementsVersions = mapOf("queued" to 1))
        val untouched = accepted("other")
        val next = ProjectTaskGraph.invalidateDependents(listOf(upstream, dependent, accepted("c", listOf("b")), untouched), "a", 10)
        assertNull(ProjectTaskGraph.validate(next))
        for (id in listOf("b", "c")) {
            val invalidated = next.first { it.id == id }
            assertEquals(ProjectTaskStatus.READY, invalidated.status)
            assertEquals(2L, invalidated.requirementsVersion)
            assertNull(invalidated.acceptedRequirementsVersion)
            assertEquals("UPSTREAM_CHANGED", invalidated.executionReason)
            assertTrue(invalidated.version > 10)
        }
        assertEquals(untouched, next.first { it.id == "other" })
        val acceptedAgain = next.map { if (it.id == "a") it.copy(status = ProjectTaskStatus.DONE, acceptedRequirementsVersion = 2) else it }
        assertFalse(ProjectTaskGraph.executionReady(acceptedAgain.first { it.id == "b" }, "queued", acceptedAgain))
        val fresh = acceptedAgain.first { it.id == "b" }.copy(executionRequirementsVersions = mapOf("queued" to 2))
        assertTrue(ProjectTaskGraph.executionReady(fresh, "queued", acceptedAgain))
        assertFalse(ProjectTaskGraph.executionReady(fresh.copy(executionRequirementsVersions = emptyMap()), "queued", acceptedAgain))
    }
    @Test fun searchAndStateFilterKeepAncestorsWithoutChangingStructure() {
        val tasks = listOf(task("root"), task("branch", "root"), task("needle", "branch").copy(status = ProjectTaskStatus.BLOCKED))
        assertEquals(listOf("root", "branch", "needle"), ProjectTaskGraph.visibleTree(tasks, "needle", ProjectTaskStatus.BLOCKED, setOf("root")).map { it.task.id })
        assertEquals(listOf("root"), ProjectTaskGraph.visibleTree(tasks, "", null, setOf("root")).map { it.task.id })
        assertEquals("branch", tasks.last().parentId)
    }
}
