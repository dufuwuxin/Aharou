package com.aharou.feature.agent.domain.tool.custom

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.plugin.data.PluginRecordStore
import dagger.Lazy
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class PromptEditTool @Inject constructor(
    private val store: ToolSettingsStore,
    private val registry: Lazy<ToolRegistry>,
    private val plugins: PluginRecordStore,
) : AgentTool() {
    override val name = "prompt_edit"
    override val description = "提前查看或修改工具、插件的模型可见注入文案，不修改执行权限或参数结构。action=list/get/set/reset；target=tool/plugin；id 为工具名或插件id；get返回version，set/reset必须带同一version。插件文案追加到该插件工具说明。配置保存后下一轮请求生效。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE, ToolCapability.WRITE_WORKSPACE)
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "操作", enum = listOf("list", "get", "set", "reset")),
        "target" to ToolParameter("target", ParameterType.STRING, "编辑对象", enum = listOf("tool", "plugin")),
        "id" to ToolParameter("id", ParameterType.STRING, "工具名或已安装插件id", required = false),
        "content" to ToolParameter("content", ParameterType.STRING, "新的文案；reset恢复默认", required = false),
        "version" to ToolParameter("version", ParameterType.STRING, "get返回的版本，防止覆盖并发修改", required = false),
    )

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> =
        if ((args["action"] as? JsonPrimitive)?.contentOrNull in setOf("list", "get"))
            setOf(ToolCapability.READ_WORKSPACE) else capabilities

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
        ToolResult.Error("A session workspace is required", "MISSING_CONTEXT")

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        fun value(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val target = value("target") ?: return ToolResult.Error("target is required", "INVALID_TARGET")
        val allowedTools = registry.get().getDeferredTools(context).associateBy { it.name }
        val allowed = allowedTools.keys
        val ids = when (target) {
            "tool" -> allowed.sorted()
            "plugin" -> plugins.all().keys.filter { allowed.any { name -> name.startsWith(ToolPromptMetadata.pluginKey(it)) } }.sorted()
            else -> return ToolResult.Error("target must be tool or plugin", "INVALID_TARGET")
        }
        val action = value("action")
        if (action == "list") return ToolResult.Success(buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
        if (action !in setOf("get", "set", "reset")) return ToolResult.Error("Invalid action", "INVALID_ACTION")
        val id = value("id")?.takeIf { it in ids } ?: return ToolResult.Error("Target not found", "NOT_FOUND")
        val key = if (target == "plugin") ToolPromptMetadata.pluginKey(id) else id
        val before = store.snapshotForProject(context.projectRoot)
        val original = {
            if (target == "plugin") {
                check(id in plugins.all()) { "Plugin removed" }
                ""
            } else before.tools.firstOrNull { it.name == id }?.description
                ?: registry.get().getTool(id)?.description ?: error("Tool removed")
        }
        val baseline = original()
        if (action != "get") {
            val version = value("version") ?: return ToolResult.Error("Read the current version first", "MISSING_VERSION")
            val content = if (action == "reset") null else (value("content")
                ?: return ToolResult.Error("content is required", "MISSING_CONTENT"))
            if (content != null && content.length > 40000) return ToolResult.Error("Content exceeds 40000 characters", "CONTENT_TOO_LONG")
            val error = store.editPrompt(key, content, version, context.projectRoot, original)
            if (error != null) return ToolResult.Error("Prompt edit rejected; read again", error)
        }
        val snapshot = if (action == "get") before else store.snapshotForProject(context.projectRoot)
        return ToolResult.Success(buildJsonObject {
            put("target", target); put("id", id)
            put("content", snapshot.overrides[key]?.description ?: baseline)
            put("version", ToolPromptMetadata.version(snapshot, key, baseline))
            put("scope", if (context.projectRoot.isBlank()) "global" else "project")
        })
    }
}
