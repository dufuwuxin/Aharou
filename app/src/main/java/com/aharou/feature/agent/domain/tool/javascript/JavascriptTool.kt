package com.aharou.feature.agent.domain.tool.javascript

import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

class JavascriptTool @Inject constructor(
    private val evaluator: JavascriptEvaluator
) : AgentTool() {
    override val name = "eval_javascript"
    override val description =
        "Evaluate local JavaScript pure computation, returning the last expression and console logs. " +
            "No DOM, Node, Java, network, files or process access. Use tool_search to discover. " +
            "Code: max 65536 characters; timeout_ms: 1..5000 (default 1000); " +
            "output: max 32768 characters; logs: max 64 entries/8192 characters; " +
            "memory: 16 MiB; stack: 256 KiB. No async results."
    override val deferredLoading = true
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val parameters = mapOf(
        "code" to ToolParameter("code", ParameterType.STRING, "JavaScript source; last expression is returned."),
        "timeout_ms" to ToolParameter(
            "timeout_ms", ParameterType.INTEGER,
            "Native execution deadline in milliseconds, 1..5000; default 1000.", required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val source = args["code"] as? JsonPrimitive
        if (source == null || !source.isString) {
            return ToolResult.Error("code must be a string", "INVALID_ARGS")
        }
        val timeout = if ("timeout_ms" in args) {
            val value = args["timeout_ms"] as? JsonPrimitive
            if (value == null || value.isString || value.longOrNull == null) {
                return ToolResult.Error("timeout_ms must be an integer", "INVALID_ARGS")
            }
            value.longOrNull ?: return ToolResult.Error("timeout_ms must be an integer", "INVALID_ARGS")
        } else {
            JavascriptEvaluator.DEFAULT_TIMEOUT_MS
        }
        return evaluator.evaluate(source.content, timeout)
    }
}
