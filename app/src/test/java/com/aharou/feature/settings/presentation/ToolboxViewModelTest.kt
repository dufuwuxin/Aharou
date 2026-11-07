package com.aharou.feature.settings.presentation

import android.content.Context
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.custom.*
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolboxViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = mockk<ToolSettingsStore>(relaxed = true)
    private val registry = mockk<ToolRegistry>(relaxed = true)
    private val engine = mockk<CommandEngine>()
    private val workspace = mockk<WorkspaceRepository>()
    private val context = mockk<Context>(relaxed = true)
    private val policy = mockk<ToolPermissionPolicyEngine>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { store.snapshotCustomTools() } returns emptyList()
        every { workspace.currentPath() } returns "/project/a"
        coEvery { policy.evaluate(any(), any(), any(), any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK, emptyList())
    }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun vm() = ToolboxViewModel(store, registry, engine, workspace, context, policy)

    @Test fun strictImportRejectsBadDefinitionsAndRoundTripsOriginalFormat() {
        val tool = CustomToolDefinition("example", command = "printf %s {{path}}", parameters = listOf(CustomToolParameter("path", required = true)))
        assertEquals(tool, ToolboxContract.parse(ToolboxContract.export(tool)))
        listOf(
            "{\"name\":\"../bad\",\"command\":\"true\"}",
            "{\"name\":\"bad\",\"command\":\"\"}",
            "{\"name\":\"bad\",\"command\":\"echo {{missing}}\"}",
            "{\"name\":\"bad\",\"command\":\"echo {{oops\"}",
            "{\"name\":\"bad\",\"command\":\"true\",\"parameters\":[{\"name\":\"x\"},{\"name\":\"x\"}]}",
            "{\"name\":\"bad\",\"command\":\"true\",\"parameters\":[{\"name\":\"x\",\"type\":\"object\"}]}",
            "{\"name\":\"bad\",\"command\":\"true\",\"timeoutSeconds\":601}",
            " ".repeat(65537)
        ).forEach { input -> assertTrue(runCatching { ToolboxContract.parse(input) }.isFailure) }
    }

    @Test fun checkTemplatesUseTheInstalledQueryNotAnUnimplementedCli() {
        listOf("check", "symbols", "map", "callers").forEach { action ->
            val tool = PracticalToolTemplates.all.single { it.name == "code_$action" }
            assertTrue(tool.command.startsWith("python3 -I \"\$HOME/.aharou/lib/codekit/query.py\" $action --path {{path}}"))
        }
    }

    @Test fun selectionParametersAndImportNeverExecute() = runTest(dispatcher) {
        val vm = vm()
        vm.select(PracticalToolTemplates.all.first { it.name == "git_status" })
        vm.setArgument("path", ".")
        vm.setImportText("{\"name\":\"draft\",\"command\":\"true\"}")
        vm.previewImport()
        advanceUntilIdle()
        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
        coVerify(exactly = 0) { store.createCustomTool(any()) }
    }

    @Test fun explicitRunBindsWorkspaceAndPreservesExitAndTruncation() = runTest(dispatcher) {
        val vm = vm()
        vm.select(CustomToolDefinition("example", command = "false"))
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("failure", 7, true)
        vm.run()
        vm.state.first { !it.running }
        advanceUntilIdle()
        assertEquals("/project/a", vm.state.value.runRoot)
        assertTrue(vm.state.value.output.contains("7"))
        assertEquals(7, vm.state.value.exitCode)
        assertTrue(vm.state.value.truncated)
        assertFalse(vm.state.value.running)
        coVerify(exactly = 1) { engine.runCommandSyncWithExit("false", "/project/a", 60000L) }
    }

    @Test fun denyDoesNotExecuteOrChangePermissions() = runTest(dispatcher) {
        coEvery { policy.evaluate(any(), any(), any(), any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.DENY, emptyList(), denyReason = "denied")
        val vm = vm()
        vm.select(CustomToolDefinition("example", command = "true"))
        vm.run()
        vm.state.first { !it.running }
        advanceUntilIdle()
        assertEquals("denied", vm.state.value.output)
        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test fun importSaveRefusesExistingNames() = runTest(dispatcher) {
        every { registry.hasTool("exists") } returns true
        val vm = vm()
        vm.setImportText("{\"name\":\"exists\",\"command\":\"true\"}")
        vm.previewImport()
        vm.saveImport()
        advanceUntilIdle()
        coVerify(exactly = 0) { store.createCustomTool(any()) }
        assertEquals(ToolboxMessage.CONFLICT, vm.state.value.message)
    }
    @Test fun changedInputsRejectLateResultsEvenWhenOldCommandIgnoresCancellation() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            withContext(NonCancellable) { release.await(); finished.complete(Unit) }
            CommandResult("obsolete", 0)
        }
        val vm = vm()
        vm.select(CustomToolDefinition("old", command = "true"))
        vm.run()
        started.await()
        vm.select(CustomToolDefinition("new", command = "false"))
        coEvery { engine.runCommandSyncWithExit("false", any(), any()) } returns CommandResult("current", 9)
        every { workspace.currentPath() } returns "/project/b"
        vm.run()
        vm.state.first { !it.running }
        release.complete(Unit)
        finished.await()
        advanceUntilIdle()
        assertFalse(vm.state.value.running)
        assertFalse(vm.state.value.output.contains("obsolete"))
        assertTrue(vm.state.value.output.contains("current"))
        assertEquals(9, vm.state.value.exitCode)
        assertEquals("/project/b", vm.state.value.runRoot)
    }

    @Test fun cancelResetsLoadingWithoutSwallowingCancellation() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val vm = vm()
        vm.select(CustomToolDefinition("example", command = "true"))
        vm.run()
        started.await()
        vm.cancel()
        cancelled.await()
        advanceUntilIdle()
        assertFalse(vm.state.value.running)
        assertEquals(ToolboxMessage.CANCELLED, vm.state.value.message)
        assertEquals("", vm.state.value.output)
    }

    @Test fun parameterTypesAreValidatedWithoutCoercingInvalidInput() {
        val tool = CustomToolDefinition("typed", command = "echo {{n}}", parameters = listOf(
            CustomToolParameter("n", CustomToolParameterType.INTEGER, required = true)))
        assertEquals("12", ToolboxContract.arguments(tool, mapOf("n" to "12")).getValue("n").toString())
        listOf("", "1.2", "$(id)", "NaN").forEach { value ->
            assertTrue(runCatching { ToolboxContract.arguments(tool, mapOf("n" to value)) }.isFailure)
        }
    }

}
