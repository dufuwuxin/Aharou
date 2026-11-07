package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.custom.*
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

enum class ToolboxMessage { INVALID, CONFLICT, SAVE_FAILED, SAVED, LOAD_FAILED, CANCELLED }

data class ToolboxUiState(
    val saved: List<CustomToolDefinition> = emptyList(),
    val selected: CustomToolDefinition? = null,
    val arguments: Map<String, String> = emptyMap(),
    val preview: String = "",
    val validation: String? = null,
    val running: Boolean = false,
    val runRoot: String = "",
    val output: String = "",
    val truncated: Boolean = false,
    val exitCode: Int? = null,
    val errorCode: String? = null,
    val importText: String = "",
    val importDraft: CustomToolDefinition? = null,
    val saving: Boolean = false,
    val exportText: String = "",
    val message: ToolboxMessage? = null,
)

/** Uses the existing on-disk definition, never a parallel schema. */
internal object ToolboxContract {
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private const val MAX_JSON = 65536

    fun parse(text: String): CustomToolDefinition {
        require(text.length <= MAX_JSON && text.toByteArray(Charsets.UTF_8).size <= MAX_JSON)
        return json.decodeFromString<CustomToolDefinition>(text).also(::validate)
    }

    fun export(tool: CustomToolDefinition): String = json.encodeToString(tool)

    fun validate(tool: CustomToolDefinition) {
        require(tool.isValidName && tool.command.isNotBlank() && tool.command.length <= 16384)
        require(tool.description.length <= 8192 && tool.parameters.size <= 32 && tool.keywords.size <= 64)
        require(tool.timeoutSeconds in CustomToolDefinition.MIN_TIMEOUT_SECONDS..CustomToolDefinition.MAX_TIMEOUT_SECONDS)
        require(tool.parameters.all { it.isValidName && it.description.length <= 8192 })
        require(tool.parameters.map { it.name }.distinct().size == tool.parameters.size)
        require(tool.keywords.all { it.length <= 256 })
        val used = CustomCommandTemplate.placeholders(tool.command)
        require(used.all { name -> tool.parameters.any { it.name == name } })
        val remainder = CustomCommandTemplate.PLACEHOLDER_REGEX.replace(tool.command, "")
        require(!remainder.contains("{{") && !remainder.contains("}}"))
    }

    fun arguments(tool: CustomToolDefinition, values: Map<String, String>): Map<String, JsonElement> =
        tool.parameters.mapNotNull { p ->
            val value = values[p.name].orEmpty()
            require(!p.required || value.isNotBlank())
            if (value.isEmpty() && !p.required) return@mapNotNull null
            require(value.length <= 16384)
            val primitive = when (p.type) {
                CustomToolParameterType.STRING -> JsonPrimitive(value)
                CustomToolParameterType.INTEGER -> JsonPrimitive(requireNotNull(value.toLongOrNull()))
                CustomToolParameterType.NUMBER -> JsonPrimitive(requireNotNull(value.toDoubleOrNull()).also { require(it.isFinite()) })
                CustomToolParameterType.BOOLEAN -> JsonPrimitive(requireNotNull(value.toBooleanStrictOrNull()))
            }
            p.name to primitive
        }.toMap()
}

@HiltViewModel
class ToolboxViewModel @Inject constructor(
    private val store: ToolSettingsStore,
    private val registry: ToolRegistry,
    private val engine: CommandEngine,
    private val workspace: WorkspaceRepository,
    @ApplicationContext private val context: Context,
    private val policy: ToolPermissionPolicyEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(ToolboxUiState())
    val state = _state.asStateFlow()
    private var generation = 0L
    private var runJob: Job? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            try {
                store.refresh()
                _state.update { it.copy(saved = store.snapshotCustomTools()) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { _state.update { it.copy(message = ToolboxMessage.LOAD_FAILED) } }
        }
    }

    private fun invalidate() {
        generation++
        runJob?.cancel()
        runJob = null
        _state.update { it.copy(running = false, output = "", truncated = false, exitCode = null, errorCode = null, runRoot = "", message = null) }
    }

    fun select(tool: CustomToolDefinition) {
        invalidate()
        _state.update { it.copy(selected = tool, arguments = emptyMap(), exportText = "") }
        updatePreview()
    }

    fun setArgument(name: String, value: String) {
        invalidate()
        _state.update { it.copy(arguments = it.arguments + (name to value)) }
        updatePreview()
    }

    private fun updatePreview() {
        val s = state.value
        val tool = s.selected ?: return
        try {
            ToolboxContract.validate(tool)
            val args = ToolboxContract.arguments(tool, s.arguments)
            val rendered = CustomCommandTemplate.renderForExecution(tool.command, tool.parameters,
                args.mapValues { (_, v) -> (v as JsonPrimitive).content })
            when (rendered) {
                is CustomCommandRender.Success -> _state.update { it.copy(preview = rendered.command, validation = null) }
                is CustomCommandRender.Failure -> _state.update { it.copy(preview = "", validation = rendered.code) }
            }
        } catch (_: IllegalArgumentException) {
            _state.update { it.copy(preview = "", validation = "INVALID_ARGUMENTS") }
        }
    }

    /** Only this explicit click path executes. No model, permissions mutation or installer. */
    fun run() {
        val s = state.value
        val definition = s.selected ?: return
        if (s.preview.isBlank() || s.validation != null) return
        invalidate()
        val id = generation
        val root = workspace.currentPath()
        if (root.isBlank()) {
            _state.update { it.copy(message = ToolboxMessage.INVALID) }
            return
        }
        _state.update { it.copy(running = true, runRoot = root) }
        runJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val args = ToolboxContract.arguments(definition, s.arguments)
                    var truncated = false
                    var exitCode: Int? = null
                    // Preserve the original engine metadata lost by CustomCommandTool's textual result.
                    val capture = object : CommandEngine by engine {
                        override suspend fun runCommandSyncWithExit(command: String, projectPath: String?, timeoutMs: Long): CommandResult =
                            engine.runCommandSyncWithExit(command, projectPath, timeoutMs).also { truncated = it.outputTruncated; exitCode = it.exitCode }
                    }
                    val tool = CustomCommandTool(definition, capture, workspace, context)
                    val verdict = policy.evaluate(tool, tool.name, args, AgentMode.BUILD, root, null)
                    val output = if (verdict.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
                        ToolResult.Error(verdict.denyReason.orEmpty(), "PERMISSION_DENIED")
                    } else {
                        tool.executeWithContext(args, AgentContext(null, null, root, null))
                    }
                    Triple(output, truncated, exitCode)
                }
                if (generation == id) {
                    val output = when (val r = result.first) {
                        is ToolResult.Success -> (r.data as? JsonPrimitive)?.content ?: r.data.toString()
                        is ToolResult.Error -> r.message
                        is ToolResult.Partial -> "${r.data}\n${r.message}"
                    }
                    _state.update { it.copy(output = output, truncated = result.second || result.first is ToolResult.Partial,
                        exitCode = result.third, errorCode = (result.first as? ToolResult.Error)?.code) }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generation == id) _state.update { it.copy(output = e.message ?: e.javaClass.simpleName) }
            } finally {
                if (generation == id) _state.update { it.copy(running = false) }
            }
        }
    }

    fun cancel() {
        invalidate()
        _state.update { it.copy(message = ToolboxMessage.CANCELLED) }
    }

    fun setImportText(text: String) {
        _state.update { it.copy(importText = text.take(65537), importDraft = null, message = null) }
    }

    fun previewImport() {
        try {
            val draft = ToolboxContract.parse(state.value.importText)
            _state.update { it.copy(importDraft = draft, message = null) }
        } catch (_: IllegalArgumentException) {
            _state.update { it.copy(importDraft = null, message = ToolboxMessage.INVALID) }
        }
    }

    fun exportSelected() {
        state.value.selected?.let { tool -> _state.update { it.copy(exportText = ToolboxContract.export(tool)) } }
    }

    fun saveImport() { state.value.importDraft?.let(::save) }
    fun saveSelected() { state.value.selected?.let(::save) }

    private fun save(definition: CustomToolDefinition) {
        if (state.value.saving) return
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            try {
                ToolboxContract.validate(definition)
                store.refresh()
                if (store.snapshotCustomTools().any { it.name == definition.name } || registry.hasTool(definition.name)) {
                    _state.update { it.copy(message = ToolboxMessage.CONFLICT) }
                } else if (store.createCustomTool(definition)) {
                    _state.update { it.copy(saved = store.snapshotCustomTools(), message = ToolboxMessage.SAVED) }
                } else {
                    _state.update { it.copy(message = ToolboxMessage.SAVE_FAILED) }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { _state.update { it.copy(message = ToolboxMessage.SAVE_FAILED) }
            } finally { _state.update { it.copy(saving = false) } }
        }
    }
}
