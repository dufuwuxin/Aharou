package com.aharou.feature.agent.domain.tool.script

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.notification.*
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.container.ExecuteCommandTool
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LiveContextScriptRunnerTest {
    private val definitions = MutableStateFlow(listOf(ScriptToolDefinition("current", "Current input", "cat", type = ScriptToolType.LIVE_CONTEXT)))
    private val manager = mockk<ScriptToolManager>()
    private val executor = mockk<ExecuteCommandTool>()
    private val registry = ToolRegistry()
    private val policy = mockk<ToolPermissionPolicyEngine>(relaxed = true)
    private val context = AgentContext(null, null, "/fixture/one", null, sessionId = "one")
    private val permissions = ToolPermissionManager()
    private val notifications = AgentNotificationCenter(mockk(relaxed = true))
    private val runner = LiveContextScriptRunner(manager, executor, Lazy { registry }, policy, permissions, mockk(), mockk(relaxed = true), notifications)

    @Before fun setup() {
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { manager.definitions } returns definitions
        every { manager.revision } returns 0L
        every { executor.name } returns "Bash"
        every { executor.effectivePermissionPolicy(any()) } returns ToolPermissionPolicy.ASK
        every { executor.redactOutput(any(), any(), any()) } answers { firstArg() }
        registry.bindDiscoveryTools(listOf(executor), context)
        coEvery { policy.evaluate(any(), any(), any(), any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW, emptyList())
    }

    @After fun teardown() { unmockkObject(FileLogger) }

    @Test fun eachRequestUsesCurrentContextAndDoesNotAccumulateOldOutput() = runTest {
        coEvery { executor.executeForeground(any(), any()) } answers {
            CommandResult(secondArg<AgentContext>().currentUserText, 0)
        }
        val first = context.copy(currentUserText = "first_value")
        val second = context.copy(currentUserText = "second_value", projectRoot = "/fixture/two", sessionId = "two")
        registry.bindDiscoveryTools(listOf(executor), second)
        assertTrue(runner.build(first).contains("first_value"))
        val next = runner.build(second)
        assertTrue(next.contains("second_value"))
        assertFalse(next.contains("first_value"))
        coVerify { executor.executeForeground(match { it.getValue("command").jsonPrimitive.content.contains("/fixture/two") }, second) }
        coVerify(exactly = 2) { policy.evaluate(executor, "Bash", any(), any(), any(), any()) }
    }

    @Test fun disabledAndOrdinaryToolsMakeZeroCalls() = runTest {
        definitions.value = listOf(definitions.value.single().copy(enabled = false), ScriptToolDefinition("tool", "Ordinary", "cat"))
        assertEquals("", runner.build(context))
        coVerify(exactly = 0) { executor.executeForeground(any(), any()) }
        coVerify(exactly = 0) { policy.evaluate(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun permissionDenialContinuesWithoutExecuting() = runTest {
        coEvery { policy.evaluate(any(), any(), any(), any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.DENY, emptyList())
        assertEquals("", runner.build(context))
        coVerify(exactly = 0) { executor.executeForeground(any(), any()) }
    }

    @Test fun timeoutContinuesAndOutputIsBounded() = runTest {
        definitions.value = listOf(definitions.value.single().copy(timeoutSeconds = 1))
        coEvery { executor.executeForeground(any(), any()) } coAnswers { delay(2000); CommandResult("late", 0) }
        assertEquals("", runner.build(context))
        definitions.value = listOf(definitions.value.single().copy(outputLimit = 4))
        coEvery { executor.executeForeground(any(), any()) } returns CommandResult("abcdefgh", 0)
        val result = runner.build(context)
        assertEquals("", result)
    }

    @Test fun cancellationPropagates() = runTest {
        coEvery { executor.executeForeground(any(), any()) } throws CancellationException("fixture cancellation")
        try { runner.build(context); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun parentTimeoutIsNotMistakenForScriptTimeout() = runTest {
        coEvery { executor.executeForeground(any(), any()) } coAnswers { delay(2000); CommandResult("late", 0) }
        try { withTimeout(10) { runner.build(context) }; fail("Parent timeout must propagate") }
        catch (_: TimeoutCancellationException) { }
    }

    @Test fun missingRoleToolSnapshotCannotExecuteGlobalBash() = runTest {
        assertEquals("", runner.build(context.copy(sessionId = "unbound")))
        coVerify(exactly = 0) { executor.executeForeground(any(), any()) }
        coVerify(exactly = 0) { policy.evaluate(any(), any(), any(), any(), any(), any()) }
    }

    private fun ask() {
        coEvery { policy.evaluate(any(), any(), any(), any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK, emptyList())
        coEvery { executor.buildPermissionPreview(any(), any(), any()) } returns null
        every { executor.buildPermissionRequest(any(), any(), any()) } answers {
            PendingToolPermission(firstArg(), "Bash", "fixture", "fixture", "fixture", "{}")
        }
    }

    @Test fun realAskApprovalAndRejectionUsePendingBatch() = runTest {
        ask()
        coEvery { executor.executeForeground(any(), any()) } returns CommandResult("approved", 0)
        val work = async { runner.build(context) }
        while (permissions.pendingBatch.value == null) yield()
        permissions.resolveBatch(permissions.pendingBatch.value!!.id, PermissionChoice.REJECT)
        assertEquals("", work.await())
        coVerify(exactly = 0) { executor.executeForeground(any(), any()) }
        val approved = async { runner.build(context) }
        while (permissions.pendingBatch.value == null) yield()
        permissions.resolveBatch(permissions.pendingBatch.value!!.id, PermissionChoice.ONCE)
        assertTrue(approved.await().contains("approved"))
        assertNull(permissions.pendingBatch.value)
    }

    @Test fun realAskTimeoutAndCoroutineCancelClearPendingBatch() = runTest {
        ask()
        definitions.value = listOf(definitions.value.single().copy(timeoutSeconds = 1))
        val timed = async { runner.build(context) }
        while (permissions.pendingBatch.value == null) yield()
        assertEquals("", timed.await())
        assertNull(permissions.pendingBatch.value)
        val cancelled = async { runner.build(context) }
        while (permissions.pendingBatch.value == null) yield()
        cancelled.cancelAndJoin()
        assertNull(permissions.pendingBatch.value)
        coVerify(exactly = 0) { executor.executeForeground(any(), any()) }
    }

    @Test fun actualSessionStopCancelsPendingApprovalAndDoesNotStopSibling() = runTest {
        ask()
        val work = async { runCatching { runner.build(context) } }
        while (permissions.pendingBatch.value == null) yield()
        notifications.enqueue("sibling", PendingNotification(AgentNotificationKind.USER_INTERRUPT, "user", "stop", NotificationOutcome.STOPPED))
        delay(50)
        assertFalse(work.isCompleted)
        notifications.enqueue("one", PendingNotification(AgentNotificationKind.USER_INTERRUPT, "user", "stop", NotificationOutcome.STOPPED))
        assertTrue(work.await().exceptionOrNull() is CancellationException)
        assertNull(permissions.pendingBatch.value)
        coVerify(exactly = 0) { executor.executeForeground(any(), any()) }
    }

    @Test fun stopBeforeApprovalPreventsScriptStartup() = runTest {
        ask()
        coEvery { executor.executeForeground(any(), any()) } returns CommandResult("must not run", 0)
        val work = async { runCatching { runner.build(context) } }
        while (permissions.pendingBatch.value == null) yield()
        val batch = checkNotNull(permissions.pendingBatch.value)
        notifications.enqueue("one", PendingNotification(AgentNotificationKind.USER_INTERRUPT, "user", "stop", NotificationOutcome.STOPPED))
        permissions.resolveBatch(batch.id, PermissionChoice.ONCE)
        val result = work.await()
        coVerify(exactly = 0) { executor.executeForeground(any(), any()) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertNull(permissions.pendingBatch.value)
    }

    @Test fun actualSessionStopCancelsRunningScript() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { executor.executeForeground(any(), any()) } coAnswers {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val work = async { runCatching { runner.build(context) } }
        started.await()
        notifications.enqueue("one", PendingNotification(AgentNotificationKind.USER_INTERRUPT, "user", "stop", NotificationOutcome.STOPPED))
        assertTrue(work.await().exceptionOrNull() is CancellationException)
        assertTrue(cancelled.isCompleted)
    }

    @Test fun executionLateEditAndAbaRevisionDiscardResult() = runTest {
        var revision = 1L
        every { manager.revision } answers { revision }
        coEvery { executor.executeForeground(any(), any()) } coAnswers {
            val original = definitions.value
            definitions.value = listOf(original.single().copy(enabled = false))
            revision++
            definitions.value = original
            revision++
            CommandResult("obsolete", 0)
        }
        assertEquals("", runner.build(context))
    }

    @Test fun realExecutorNonzeroUnknownAndTruncatedOutputNeverInject() = runTest {
        val engine = mockk<com.aharou.feature.agent.domain.container.CommandEngine>()
        val settings = mockk<com.aharou.feature.settings.data.repository.ContainerSettingsRepository>()
        every { settings.redactEnvOutputEnabledSnapshot() } returns true
        val real = ExecuteCommandTool(engine, mockk(), mockk(), settings)
        registry.bindDiscoveryTools(listOf(real), context)
        val consumer = LiveContextScriptRunner(manager, real, Lazy { registry }, policy, permissions, mockk(), mockk(relaxed = true), notifications)
        for (result in listOf(CommandResult("stale_value\n[退出码: 0]", 7), CommandResult("unknown", null), CommandResult("partial", 0, true))) {
            coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns result
            assertEquals("", consumer.build(context))
        }
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("fresh_value", 0)
        assertTrue(consumer.build(context).contains("fresh_value"))
        coVerify(exactly = 4) { policy.evaluate(real, "Bash", any(), any(), any(), any()) }
    }

    @Test fun disablingWhileRealApprovalIsPendingCannotRunSavedCommand() = runTest {
        ask()
        var revision = 1L
        every { manager.revision } answers { revision }
        val work = async { runner.build(context) }
        while (permissions.pendingBatch.value == null) yield()
        val pendingId = permissions.pendingBatch.value!!.id
        definitions.value = listOf(definitions.value.single().copy(enabled = false))
        revision++
        permissions.resolveBatch(pendingId, PermissionChoice.ONCE)
        assertEquals("", work.await())
        assertNull(permissions.pendingBatch.value)
        coVerify(exactly = 0) { executor.executeForeground(any(), any()) }
    }

    @Test fun editDuringApprovalCannotExecuteObsoleteCommand() = runTest {
        coEvery { policy.evaluate(any(), any(), any(), any(), any(), any()) } coAnswers {
            definitions.value = listOf(definitions.value.single().copy(command = "changed"))
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW, emptyList())
        }
        assertEquals("", runner.build(context))
        coVerify(exactly = 0) { executor.executeForeground(any(), any()) }
    }
}
