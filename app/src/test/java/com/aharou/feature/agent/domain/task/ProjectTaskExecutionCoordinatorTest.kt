package com.aharou.feature.agent.domain.task

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProjectTaskExecutionCoordinatorTest {
    @Test fun softStopIsStickyEvenWhenWorkflowLaterCompletesNormally() {
        val coordinator = ProjectTaskExecutionCoordinator()
        coordinator.begin("s", "t")
        coordinator.stop("s")
        coordinator.finish("s", "t", ProjectTaskExecutionOutcome.SUCCEEDED)
        assertEquals(ProjectTaskExecutionOutcome.STOPPED, coordinator.outcome("s", "t"))
        assertEquals(ProjectTaskStatus.BLOCKED, ProjectTaskGraph.executionStatus(ProjectTaskExecutionOutcome.STOPPED, false))
        coordinator.begin("s", "success")
        coordinator.finish("s", "success", ProjectTaskExecutionOutcome.SUCCEEDED)
        coordinator.failIfRunning("s", "success")
        assertEquals(ProjectTaskExecutionOutcome.SUCCEEDED, coordinator.outcome("s", "success"))
        coordinator.begin("s", "next")
        coordinator.finish("s", "next", ProjectTaskExecutionOutcome.FAILED)
        assertEquals(ProjectTaskExecutionOutcome.FAILED, coordinator.outcome("s", "next"))
    }
    @Test fun workspaceLockFollowsActualTurnRootInsteadOfAnOlderSessionBinding() {
        val coordinator = ProjectTaskExecutionCoordinator()
        assertEquals("/actual", coordinator.executionWorkspace("/actual", "/old", "/selected"))
        assertEquals("/old", coordinator.executionWorkspace("", "/old", "/selected"))
        assertEquals("/selected", coordinator.executionWorkspace("", "", "/selected"))
    }
    @Test fun normalizedWorkspaceRootsSerializeAndCancelledWaiterReleasesNothing() = runBlocking {
        val coordinator = ProjectTaskExecutionCoordinator()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = launch { coordinator.withWorkspace("/repo/a/../project/") { entered.complete(Unit); release.await() } }
        entered.await()
        val otherEntered = CompletableDeferred<Unit>()
        val other = launch { coordinator.withWorkspace("/repo/other") { otherEntered.complete(Unit) } }
        withTimeout(1000) { otherEntered.await() }; other.join()
        var secondEntered = false
        val second = launch { coordinator.withWorkspace("/repo/project") { secondEntered = true } }
        yield(); assertFalse(secondEntered)
        second.cancelAndJoin(); release.complete(Unit); first.join()
        coordinator.withWorkspace("/repo/project/") { secondEntered = true }
        assertTrue(secondEntered)
        coordinator.withWorkspace("/repo/other") { assertTrue(true) }
    }
}
