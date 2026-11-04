package com.aharou.feature.settings.presentation

import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolCapability
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.custom.CustomToolDefinition
import com.aharou.feature.agent.domain.tool.custom.CustomToolParameter
import com.aharou.feature.agent.domain.tool.custom.ToolInjectionMode
import com.aharou.feature.agent.domain.tool.custom.ToolOverride
import com.aharou.feature.agent.domain.tool.custom.ToolSchemaOverride
import com.aharou.feature.agent.domain.tool.custom.PracticalToolTemplates
import com.aharou.feature.agent.domain.tool.custom.ToolSettingsStore
import com.google.gson.GsonBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 工具来源：自建 / 内置 / MCP（界面据此分组与做删除权限判定）。 */
enum class ToolSource { CUSTOM, BUILTIN, MCP }

/** 编辑目标：[name] 为 null 表示新建，否则编辑该名字的工具。 */
data class ToolEditorTarget(val name: String? = null, val template: CustomToolDefinition? = null)

/** 一次性反馈（界面转成 Toast 后清掉），避免失败被静默吞掉。 */
enum class ToolsMessage { SAVE_FAILED, DELETE_FAILED, LOAD_FAILED }

/**
 * 工具页的一行。
 *
 * [schemaJson] 是兼容覆盖后的 schema；[originalSchemaJson] 保留原始执行契约供编辑校验；
 * [parametersJson] 只在自定义工具上有值，是编辑器里那份可改的参数 JSON。
 */
data class ToolUiEntry(
    val name: String,
    val description: String,
    val source: ToolSource,
    val schemaJson: String,
    val injectionMode: ToolInjectionMode,
    val keywords: List<String>,
    val command: String = "",
    val parametersJson: String = "",
    val timeoutSeconds: Int = CustomToolDefinition.DEFAULT_TIMEOUT_SECONDS,
    val permission: com.aharou.feature.agent.domain.tool.ToolPermissionPolicy = com.aharou.feature.agent.domain.tool.ToolPermissionPolicy.ASK,
    val deferred: Boolean = false,
    val category: ToolCategory = ToolCategory.OTHER,
    val originalSchemaJson: String = schemaJson,
)

data class ToolsUiState(
    val entries: List<ToolUiEntry> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val editor: ToolEditorTarget? = null,
    val message: ToolsMessage? = null
)

/**
 * 工具管理页的状态与动作。
 *
 * 读走 [ToolSettingsStore] 的内存快照（同步、无 IO），写一律切到 `Dispatchers.IO`；
 * 写完只重建界面列表，不用「失败也 refresh」把错误盖掉——失败必须让用户看见。
 */
@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val store: ToolSettingsStore,
    private val registry: ToolRegistry
) : ViewModel() {

    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    val entries: List<ToolUiEntry> get() = state.value.entries
    fun grouped(): List<Pair<ToolCategory, List<ToolUiEntry>>> = ToolCategory.entries.mapNotNull { category ->
        entries.filter { it.category == category }.takeIf { it.isNotEmpty() }?.let { category to it }
    }

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    init {
        refresh()
    }

    /** 重新从磁盘读取并重建列表（进入页面 / 外部改动后调用）。 */
    fun refresh() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching {
                    store.refresh()
                    buildEntries()
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            }
            _state.update {
                if (entries == null) {
                    it.copy(loading = false, message = ToolsMessage.LOAD_FAILED)
                } else {
                    it.copy(entries = entries, loading = false)
                }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    // ---- 编辑面板 ----

    fun startCreate() {
        _state.update { it.copy(editor = ToolEditorTarget(null)) }
    }

    fun startTemplate(name: String) {
        val template = PracticalToolTemplates.all.firstOrNull { it.name == name } ?: return
        _state.update { it.copy(editor = ToolEditorTarget(template = template)) }
    }

    fun openEditor(name: String) {
        _state.update { it.copy(editor = ToolEditorTarget(name)) }
    }

    fun closeEditor() {
        _state.update { it.copy(editor = null) }
    }

    // ---- 自定义工具 ----

    fun saveCustomTool(
        name: String,
        description: String,
        command: String,
        parameters: List<CustomToolParameter>,
        timeoutSeconds: Int,
        injectionMode: ToolInjectionMode,
        keywords: List<String>
    ) {
        mutate(
            failure = ToolsMessage.SAVE_FAILED,
            write = {
                store.saveCustomTool(
                    CustomToolDefinition(
                        name = name,
                        description = description,
                        command = command,
                        parameters = parameters,
                        timeoutSeconds = timeoutSeconds,
                        injectionMode = injectionMode,
                        keywords = keywords
                    )
                )
            }
        )
    }

    fun deleteCustomTool(name: String) {
        mutate(failure = ToolsMessage.DELETE_FAILED, write = { store.deleteCustomTool(name) })
    }

    // ---- 内置 / MCP 工具 ----

    fun setInjection(name: String, mode: ToolInjectionMode) = updateSettings(name, mode = mode)

    fun setKeywords(name: String, keywords: List<String>) = updateSettings(name, keywords = keywords)

    fun setDescriptionOverride(name: String, description: String?) =
        updateSettings(name, description = description)

    /** 编辑器保存：一次写回描述、注入方式、关键词与兼容的 schema 元数据。 */
    fun saveToolSettings(name: String, description: String?, mode: ToolInjectionMode, keywords: List<String>, schemaJson: String?) =
        updateSettings(name, mode = mode, keywords = keywords, description = description,
            schemaJson = schemaJson, replaceEditorFields = true)

    private fun updateSettings(
        name: String,
        mode: ToolInjectionMode? = null,
        keywords: List<String>? = null,
        description: String? = null,
        schemaJson: String? = null,
        replaceEditorFields: Boolean = false
    ) {
        val custom = store.snapshotCustomTool(name)
        if (custom != null) {
            // 自建工具的注入方式落在它自己的定义文件里
            val target = when {
                mode != null -> custom.copy(injectionMode = mode)
                keywords != null -> custom.copy(keywords = keywords)
                else -> custom.copy(description = description.orEmpty())
            }
            mutate(failure = ToolsMessage.SAVE_FAILED, write = { store.saveCustomTool(target) })
            return
        }
        val previous = store.snapshotOverrides()[name]
        val merged = ToolOverride(
            description = if (replaceEditorFields) description else description ?: previous?.description,
            injectionMode = mode ?: previous?.injectionMode,
            keywords = keywords ?: previous?.keywords,
            schemaJson = if (replaceEditorFields) schemaJson else previous?.schemaJson
        ).normalized()
        // 全部回到默认值（每轮注入 + 无描述覆盖 + 无关键词）等价于没有覆盖，落盘时直接删条目
        val isDefault = merged.injectionMode == ToolInjectionMode.EVERY_ROUND &&
            merged.description == null && merged.keywords.isNullOrEmpty() && merged.schemaJson == null
        mutate(
            failure = ToolsMessage.SAVE_FAILED,
            write = {
                val original = registry.getTool(name)?.toJsonSchema() ?: return@mutate false
                if (merged.schemaJson != null && !ToolSchemaOverride.isCompatible(original, merged.schemaJson)) return@mutate false
                if (isDefault || merged.isEmpty) store.clearOverride(name) else store.saveOverride(name, merged)
            }
        )
    }

    // ---- 内部 ----

    /** 写操作统一包装：IO 调度、成功才重建列表、失败给一条明确的界面反馈。 */
    private fun mutate(failure: ToolsMessage, write: suspend () -> Boolean) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { write() }.onFailure { if (it is CancellationException) throw it }.getOrDefault(false)
            }
            if (!result) {
                _state.update { it.copy(saving = false, message = failure) }
                return@launch
            }
            val entries = withContext(Dispatchers.IO) { runCatching { buildEntries() }.onFailure { if (it is CancellationException) throw it }.getOrNull() }
            _state.update {
                it.copy(
                    saving = false,
                    editor = null,
                    entries = entries ?: it.entries,
                    message = if (entries == null) ToolsMessage.LOAD_FAILED else null
                )
            }
        }
    }

    private fun buildEntries(): List<ToolUiEntry> {
        val customs = store.snapshotCustomTools().associateBy { it.name }
        val overrides = store.snapshotOverrides()
        return registry.getAvailableTools().mapNotNull { tool ->
            runCatching {
                val custom = customs[tool.name]
                val override = overrides[tool.name]
                ToolUiEntry(
                    name = tool.name,
                permission = tool.permissionPolicy,
                deferred = tool.deferredLoading,
                category = categoryOf(tool),
                    // 与注入闸门同一口径：有覆盖描述就显示覆盖后的，用户看到的就是模型看到的
                    description = override?.description ?: tool.description,
                    source = when {
                        custom != null -> ToolSource.CUSTOM
                        tool.name.startsWith(MCP_NAME_PREFIX) -> ToolSource.MCP
                        else -> ToolSource.BUILTIN
                    },
                    originalSchemaJson = gson.toJson(tool.toJsonSchema()),
                    schemaJson = gson.toJson(ToolSchemaOverride.apply(tool.toJsonSchema(), override?.schemaJson)),
                    injectionMode = custom?.injectionMode
                        ?: override?.injectionMode
                        ?: ToolInjectionMode.EVERY_ROUND,
                    keywords = custom?.keywords ?: override?.keywords.orEmpty(),
                    command = custom?.command.orEmpty(),
                    parametersJson = custom?.let { prettyJson.encodeToString(it.parameters) }.orEmpty(),
                    timeoutSeconds = custom?.timeoutSeconds ?: CustomToolDefinition.DEFAULT_TIMEOUT_SECONDS
                )
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        }
    }

    private companion object {
        /** MCP 工具的命名空间前缀，见 McpTool.buildNamespacedName。 */
        const val MCP_NAME_PREFIX = "mcp__"
    }
    private fun categoryOf(tool: AgentTool): ToolCategory {
        val capabilities = tool.capabilities
        return when {
            ToolCapability.EXECUTE_COMMANDS in capabilities -> ToolCategory.COMMAND
            ToolCapability.WRITE_WORKSPACE in capabilities || ToolCapability.READ_WORKSPACE in capabilities ->
                ToolCategory.FILE
            ToolCapability.NETWORK_WRITE in capabilities || ToolCapability.NETWORK_READ in capabilities ->
                ToolCategory.NETWORK
            ToolCapability.READ_DEVICE_DATA in capabilities ||
                ToolCapability.MODIFY_DEVICE_STATE in capabilities -> ToolCategory.DEVICE
            ToolCapability.MODIFY_AGENT_CONFIG in capabilities ||
                ToolCapability.READ_AGENT_CONFIG in capabilities ||
                ToolCapability.MODIFY_SESSION_STATE in capabilities ||
                ToolCapability.MODIFY_TODO_STATE in capabilities ||
                ToolCapability.USER_INTERACTION in capabilities -> ToolCategory.AGENT
            ToolCapability.EXTERNAL_TOOL in capabilities -> ToolCategory.EXTERNAL
            else -> ToolCategory.OTHER
        }
    }
}

enum class ToolCategory { FILE, COMMAND, NETWORK, DEVICE, AGENT, EXTERNAL, OTHER }
