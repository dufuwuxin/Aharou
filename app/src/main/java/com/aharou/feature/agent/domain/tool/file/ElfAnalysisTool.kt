// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.agent.domain.tool.file

import com.aharou.feature.agent.domain.binary.SomcpAnalysisService
import com.aharou.feature.agent.domain.binary.SomcpAnalyzer
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class ElfAnalysisTool @Inject constructor(private val analysis: SomcpAnalysisService) : AgentTool() {
    override val name = "elf_analyze"
    override val description = "本地只读分析 ELF/.so：头信息、节区、动态依赖、符号、导入导出、字符串和重定位。分析内核随应用内置，直接读取文件，不执行或修改目标。最大 32 MiB，结果分页；不提供 Rizin/LIEF 反编译或动态调试。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val deferredLoading = true
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "ELF/.so 文件路径，与 readFile 使用相同的会话工作区后端。"),
        "action" to ToolParameter("action", ParameterType.STRING, "分析内容，默认 overview。", required = false, enum = SomcpAnalyzer.ACTIONS),
        "offset" to ToolParameter("offset", ParameterType.INTEGER, "从零开始的结果偏移，按 next_offset 续读。", required = false),
        "limit" to ToolParameter("limit", ParameterType.INTEGER, "每页 1–100 项，默认 50。", required = false),
        "query" to ToolParameter("query", ParameterType.STRING, "可选文本过滤，最多 256 字符。", required = false)
    )
    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        return try {
        val path = (args["path"] as? JsonPrimitive)?.contentOrNull ?: return ToolResult.Error("缺少文件路径", "MISSING_PATH")
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull ?: "overview"
        val offset = args["offset"]?.let { (it as? JsonPrimitive)?.intOrNull ?: error("ELF_PAGE_INVALID") } ?: 0
        val limit = args["limit"]?.let { (it as? JsonPrimitive)?.intOrNull ?: error("ELF_PAGE_INVALID") } ?: 50
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(action in SomcpAnalyzer.ACTIONS) { "ELF_ACTION_UNAVAILABLE" }
        require(offset >= 0 && limit in 1..100 && query.length <= 256) { "ELF_PAGE_INVALID" }
        ToolResult.Success(analysis.analyzePath(context.projectRoot, path, action, offset, limit, query))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: IllegalArgumentException) { ToolResult.Error(failure.message ?: "无效 ELF 或参数", "ELF_INVALID_INPUT") }
    catch (failure: Exception) { ToolResult.Error(failure.message ?: "本地 ELF 分析失败", "ELF_ANALYSIS_FAILED") }
    }
}
