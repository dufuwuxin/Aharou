package com.aharou.feature.agent.domain.tool.custom

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.StreamingAgentTool
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.ToolStreamEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * 工具注入闸门：把「每轮注入 / 关键词注入 / 关闭」三态与「描述覆盖」落到某一轮的 tools 数组上。
 *
 * 所有判定都读 [ToolSettingsStore] 的内存快照，**不做磁盘 IO**——它就在每轮构建工具列表的热路径上。
 *
 * 关键词匹配口径（与界面提示一致）：大小写不敏感、忽略空白、子串匹配「当前轮用户消息」。
 * [userText] 为 null 表示这一轮没有用户文本（如上下文压缩），此时关键词工具照常注入，
 * 只有显式「关闭」的工具被剔除——宁可多给几个工具，也不要让压缩请求的 schema 与主请求忽然不一致。
 */
@Singleton
class ToolInjectionGate @Inject constructor(
    private val store: ToolSettingsStore
) {

    suspend fun refresh() = store.refresh()

    suspend fun snapshotForProject(projectRoot: String): ToolSettingsStore.Snapshot =
        store.snapshotForProject(projectRoot)

    fun visible(tools: List<AgentTool>, userText: String?): List<AgentTool> = visible(
        tools, userText, ToolSettingsStore.Snapshot(store.snapshotCustomTools(), store.snapshotOverrides())
    )

    fun visible(tools: List<AgentTool>, userText: String?, snapshot: ToolSettingsStore.Snapshot): List<AgentTool> {
        val customs = snapshot.tools.associateBy { it.name }
        val overrides = snapshot.overrides
        if (customs.isEmpty() && overrides.isEmpty()) return tools

        return tools.mapNotNull { tool ->
            val custom = customs[tool.name]
            val override = overrides[tool.name]
            val mode = custom?.injectionMode
                ?: override?.injectionMode
                ?: ToolInjectionMode.EVERY_ROUND
            val keywords = custom?.keywords ?: override?.keywords.orEmpty()
            when {
                mode == ToolInjectionMode.OFF -> null
                // 关键词为空时按「每轮注入」处理：否则工具永远不出现，用户只会以为工具坏了。
                mode == ToolInjectionMode.KEYWORD && keywords.isNotEmpty() &&
                    userText != null && !matchesAny(keywords, userText) -> null
                else -> withDescriptionOverride(tool, override?.description)
            }
        }
    }

    /** 关键词是否命中当前轮用户文本（大小写不敏感、去空白、子串匹配）。 */
    fun matchesAny(keywords: List<String>, userText: String): Boolean {
        val text = normalizeForMatch(userText)
        if (text.isEmpty()) return false
        return keywords.any { keyword ->
            val needle = normalizeForMatch(keyword)
            needle.isNotEmpty() && text.contains(needle)
        }
    }

    private fun normalizeForMatch(raw: String): String =
        raw.lowercase().filterNot { it.isWhitespace() }

    private fun withDescriptionOverride(tool: AgentTool, description: String?): AgentTool {
        val override = description?.trim().orEmpty()
        if (override.isEmpty() || override == tool.description) return tool
        return if (tool is StreamingAgentTool) {
            DescriptionOverrideStreamingTool(tool, override)
        } else {
            DescriptionOverrideTool(tool, override)
        }
    }
}

/**
 * 只替换描述的工具装饰器：schema 里若带顶层 `description` 一并替换，其余全部委托给原工具。
 *
 * 写法照抄 [com.aharou.feature.agent.domain.tool.ToolCallGuard] 的 wrapper——流式工具必须换一个
 * 同样实现 [StreamingAgentTool] 的包装，否则 `is StreamingAgentTool` 判定失败，流式输出会退化成
 * 一次性返回（Bash 这类工具尤其明显）。
 */
private class DescriptionOverrideTool(
    private val delegate: AgentTool,
    private val overrideDescription: String
) : AgentTool() {

    override val name: String get() = delegate.name
    override val description: String get() = overrideDescription
    override val parameters: Map<String, ToolParameter> get() = delegate.parameters
    override val permissionPolicy: ToolPermissionPolicy get() = delegate.permissionPolicy
    override val capabilities: Set<ToolCapability> get() = delegate.capabilities
    override val acceptsSecrets: Boolean get() = delegate.acceptsSecrets
    override val deferredLoading: Boolean get() = delegate.deferredLoading

    override fun toJsonSchema(): Map<String, Any> = withDescription(delegate.toJsonSchema(), overrideDescription)

    override fun effectivePermissionPolicy(mode: AgentMode): ToolPermissionPolicy =
        delegate.effectivePermissionPolicy(mode)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> =
        delegate.effectiveCapabilities(args)

    override fun permissionCommand(args: Map<String, JsonElement>): String? = delegate.permissionCommand(args)

    override fun isCallSensitive(args: Map<String, JsonElement>): Boolean = delegate.isCallSensitive(args)

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission = delegate.buildPermissionRequest(callId, args, argsPreview)

    override suspend fun buildPermissionPreview(
        callId: String,
        args: Map<String, JsonElement>,
        context: AgentContext
    ): PendingToolPermission? = delegate.buildPermissionPreview(callId, args, context)

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = delegate.execute(args)

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult =
        delegate.executeWithContext(args, context)
}

/** [DescriptionOverrideTool] 的流式版本：执行侧完全委托，保留 [StreamingAgentTool] 能力。 */
private class DescriptionOverrideStreamingTool(
    private val delegate: AgentTool,
    private val overrideDescription: String
) : AgentTool(), StreamingAgentTool {

    override val name: String get() = delegate.name
    override val description: String get() = overrideDescription
    override val parameters: Map<String, ToolParameter> get() = delegate.parameters
    override val permissionPolicy: ToolPermissionPolicy get() = delegate.permissionPolicy
    override val capabilities: Set<ToolCapability> get() = delegate.capabilities
    override val acceptsSecrets: Boolean get() = delegate.acceptsSecrets
    override val deferredLoading: Boolean get() = delegate.deferredLoading

    override fun toJsonSchema(): Map<String, Any> = withDescription(delegate.toJsonSchema(), overrideDescription)

    override fun effectivePermissionPolicy(mode: AgentMode): ToolPermissionPolicy =
        delegate.effectivePermissionPolicy(mode)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> =
        delegate.effectiveCapabilities(args)

    override fun permissionCommand(args: Map<String, JsonElement>): String? = delegate.permissionCommand(args)

    override fun isCallSensitive(args: Map<String, JsonElement>): Boolean = delegate.isCallSensitive(args)

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission = delegate.buildPermissionRequest(callId, args, argsPreview)

    override suspend fun buildPermissionPreview(
        callId: String,
        args: Map<String, JsonElement>,
        context: AgentContext
    ): PendingToolPermission? = delegate.buildPermissionPreview(callId, args, context)

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = delegate.execute(args)

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult =
        delegate.executeWithContext(args, context)

    override fun executeStream(args: Map<String, JsonElement>, context: AgentContext): Flow<ToolStreamEvent> =
        (delegate as StreamingAgentTool).executeStream(args, context)
}

/** schema 里带顶层 `description` 时替换掉；其它键（含 MCP 透传的任意结构）原样保留。 */
private fun withDescription(schema: Map<String, Any>, description: String): Map<String, Any> =
    if (!schema.containsKey("description")) schema else LinkedHashMap(schema).apply { put("description", description) }
