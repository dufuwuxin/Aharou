package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.viewModelScope
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
    private val viewModels = mutableListOf<ToolboxViewModel>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { store.snapshotCustomTools() } returns emptyList()
        every { workspace.currentPath() } returns "/project/a"
        coEvery { policy.evaluate(any(), any(), any(), any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK, emptyList())
    }
    @After fun cleanup() {
        try {
            runTest(dispatcher) {
                viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally { Dispatchers.resetMain() }
    }
    private fun vm() = ToolboxViewModel(store, registry, engine, workspace, context, policy).also(viewModels::add)

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

    @Test fun duplicateNewAndTemplateCreationNeverUpsertsOriginal() = runTest(dispatcher) {
        var original: CustomToolDefinition? = null
        every { registry.hasTool(any()) } returns false
        every { store.snapshotCustomTools() } answers { listOfNotNull(original) }
        coEvery { store.createCustomTool(any()) } coAnswers {
            if (original != null) false else { original = firstArg(); true }
        }
        val vm = ToolsViewModel(store, registry)
        vm.startCreate()
        vm.saveCustomTool("git_status", "original", "echo original", emptyList(), 60,
            ToolInjectionMode.EVERY_ROUND, emptyList())
        vm.state.first { !it.saving }
        val before = original
        vm.startTemplate("git_status")
        vm.saveCustomTool("git_status", "replacement", "echo replacement", emptyList(), 60,
            ToolInjectionMode.EVERY_ROUND, emptyList())
        vm.state.first { !it.saving }
        assertEquals(before, original)
        assertEquals(ToolsMessage.NAME_CONFLICT, vm.state.value.message)
        assertNotNull(vm.state.value.editor)
        coVerify(exactly = 2) { store.createCustomTool(any()) }
        coVerify(exactly = 0) { store.saveCustomTool(any()) }
    }

    @Test fun draftReopenedAsNewPreservesContentAndCannotOverwriteSavedTool() = runTest(dispatcher) {
        val existing = CustomToolDefinition("existing", command = "echo original")
        val draft = CustomToolDefinition("draft", description = "draft content", command = "echo draft")
        every { registry.hasTool(any()) } returns false
        every { store.snapshotCustomTools() } returns listOf(existing)
        coEvery { store.createCustomTool(any()) } returns false
        val vm = ToolsViewModel(store, registry)
        vm.startDraft(draft)
        assertEquals(draft, vm.state.value.editor?.template)
        assertNull(vm.state.value.editor?.name)
        vm.saveCustomTool(existing.name, draft.description, draft.command, draft.parameters, 60,
            ToolInjectionMode.EVERY_ROUND, emptyList())
        vm.state.first { !it.saving }
        assertEquals(ToolsMessage.NAME_CONFLICT, vm.state.value.message)
        assertEquals(draft, vm.state.value.editor?.template)
        assertEquals(existing, store.snapshotCustomTools().single())
        coVerify(exactly = 1) { store.createCustomTool(any()) }
        coVerify(exactly = 0) { store.saveCustomTool(any()) }
    }

    @Test fun editingUsesUpsertRatherThanCreateOnly() = runTest(dispatcher) {
        coEvery { store.saveCustomTool(any()) } returns true
        val vm = ToolsViewModel(store, registry)
        vm.openEditor("existing")
        vm.saveCustomTool("existing", "updated", "echo new", emptyList(), 60,
            ToolInjectionMode.EVERY_ROUND, emptyList())
        vm.state.first { !it.saving }
        coVerify(exactly = 1) { store.saveCustomTool(any()) }
        coVerify(exactly = 0) { store.createCustomTool(any()) }
    }

    @Test fun historyRetainsOutputButCannotRunOrExportUntilFreshSelection() = runTest(dispatcher) {
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("history", 7, true)
        val vm = vm()
        vm.select(CustomToolDefinition("example", command = "echo old"))
        vm.run()
        vm.state.first { !it.running }
        vm.exportSelected()
        val completed = vm.state.value
        vm.invalidateSelection()
        vm.run()
        vm.exportSelected()
        assertEquals(completed.output, vm.state.value.output)
        assertEquals(completed.exitCode, vm.state.value.exitCode)
        assertEquals(completed.truncated, vm.state.value.truncated)
        assertEquals(completed.runRoot, vm.state.value.runRoot)
        assertEquals("", vm.state.value.exportText)
        assertFalse(vm.state.value.selectionReady)
        coVerify(exactly = 1) { engine.runCommandSyncWithExit("echo old", any(), any()) }
        vm.select(CustomToolDefinition("example", command = "echo new"))
        vm.run()
        vm.state.first { !it.running }
        coVerify(exactly = 1) { engine.runCommandSyncWithExit("echo new", any(), any()) }
        coVerify(exactly = 0) { store.createCustomTool(any()) }
    }

}
