package com.aharou.feature.agent.domain.tool.custom

import android.content.Context
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.ContainerOutputSanitizer
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 把一条用户自定义工具（[CustomToolDefinition]）适配成可执行的 [AgentTool]：
 * schema 由参数表生成，执行时渲染命令模板并丢给当前执行环境（本地容器 / 远程 SSH）。
 *
 * 与 [com.aharou.feature.agent.domain.tool.container.ExecuteCommandTool] 保持一致的三件事：
 * 权限一律 [ToolPermissionPolicy.ASK]、能力声明为 [ToolCapability.EXECUTE_COMMANDS]、
 * 出口统一走 [ContainerOutputSanitizer] + 退出码。区别只在于命令由模板渲染而来，
 * 而不是模型直接给整条命令——用户配好的固定命令，模型只能填参数，注入面小得多。
 *
 * 刻意不实现 [com.aharou.feature.agent.domain.tool.StreamingAgentTool]：自定义命令多是一次性
 * 查询/动作，逐行流式展示收益不大，反而让 UI 要为它单独适配。
 */
class CustomCommandTool(
    val definition: CustomToolDefinition,
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val appContext: Context,
) : AgentTool() {

    override val name: String = definition.name

    override val description: String =
        definition.description.ifBlank { "Custom command tool ${definition.name}" }

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = definition.toToolParameters()

    /**
     * 按参数表生成 schema。**不注入** `tool_title`（见 [AgentTool.TOOL_TITLE_PARAM]）：
     * 自定义工具的 schema 是用户自己写的、参数名与命令模板一一对应，凭空多一个人话标题参数
     * 会让模型把 `tool_title` 也往命令里塞。`declaresToolTitle()` 因此自然为 false
     * （与 [com.aharou.feature.agent.domain.mcp.McpTool] 同理），主循环也不会催模型补标题。
     */
    override fun toJsonSchema(): Map<String, Any> {
        val properties = LinkedHashMap<String, Any>()
        val required = mutableListOf<String>()
        definition.parameters.forEach { param ->
            val prop = LinkedHashMap<String, Any>()
            prop["type"] = param.type.jsonType
            prop["description"] = param.description.ifBlank { param.name }
            properties[param.name] = prop
            if (param.required) required.add(param.name)
        }
        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val rendered = when (val result = renderCommand(args)) {
            is CustomCommandRender.Success -> result.command
            // 渲染不出来（缺参 / 模板有问题）也要让用户看到原始模板，别给个空白确认框
            is CustomCommandRender.Failure -> definition.command
        }
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = appContext.getString(R.string.tools_permission_title),
            summary = rendered,
            details = appContext.getString(R.string.tools_permission_details, definition.name, definition.timeoutSeconds.coerceIn(CustomToolDefinition.MIN_TIMEOUT_SECONDS, CustomToolDefinition.MAX_TIMEOUT_SECONDS)),
            argsPreview = argsPreview
        )
    }

    override fun permissionCommand(args: Map<String, JsonElement>): String? =
        (renderCommand(args) as? CustomCommandRender.Success)?.command

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val rendered = when (val result = renderCommand(args)) {
            is CustomCommandRender.Failure -> return ToolResult.Error(result.message, result.code)
            is CustomCommandRender.Success -> result.command
        }
        return try {
            // 与 ExecuteCommandTool 同一根目录：会话绑定的工作区，没绑定就退到全局当前工作区。
            val workdir = context.projectRoot.takeIf { it.isNotBlank() } ?: workspaceRepository.currentPath()
            val timeoutMs = definition.timeoutSeconds
                .coerceIn(CustomToolDefinition.MIN_TIMEOUT_SECONDS, CustomToolDefinition.MAX_TIMEOUT_SECONDS) * 1000L
            FileLogger.d(TAG, "Custom tool ${definition.name} execution (timeout=${timeoutMs}ms)")
            val result = commandEngine.runCommandSyncWithExit(rendered, workdir, timeoutMs)
            ToolResult.Success(JsonPrimitive(toModelOutput(result.output, result.exitCode)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "自定义工具 ${definition.name} 执行失败", e)
            ToolResult.Error("执行自定义工具失败: ${e.message}")
        }
    }

    /** 按参数表把调用入参取成字符串，再交给模板渲染。非标量入参（对象/数组）按缺失处理。 */
    private fun renderCommand(args: Map<String, JsonElement>): CustomCommandRender {
        val values = definition.parameters.associate { param ->
            param.name to (args[param.name] as? JsonPrimitive)?.contentOrNull
        }
        return CustomCommandTemplate.renderForExecution(definition.command, definition.parameters, values)
    }

    /**
     * 结果出口与 [com.aharou.feature.agent.domain.tool.container.ExecuteCommandTool] 对齐：
     * 先清洗控制字符，再拼退出码（拿不到退出码就不拼，别让模型把未知当成功）。
     */
    private fun toModelOutput(output: String, exitCode: Int?): String {
        val cleaned = ContainerOutputSanitizer.sanitize(output)
        val line = exitCode?.let { "[退出码: $it]" } ?: return cleaned
        return if (cleaned.isBlank()) line else "$cleaned\n$line"
    }

    private companion object {
        const val TAG = "CustomCommandTool"
    }
}
