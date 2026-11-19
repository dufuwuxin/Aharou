package com.aharou.feature.agent.domain.tool.file

import com.aharou.feature.agent.domain.binary.GeneralFileInspector
import com.aharou.feature.agent.domain.binary.SomcpAnalysisService
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class FileAnalysisTool @Inject constructor(private val service: SomcpAnalysisService) : AgentTool() {
    override val name = "file_analyze"
    override val description = "本地只读分析任意格式文件：真实格式、大小、SHA-256、头部；文本可分页，ZIP/APK/Office 压缩包可分页查看目录而不解压。最大 32 MiB。ELF 专用节区符号分析用 elf_analyze；识别格式不代表已实现反编译、图片解码或文档语义解析。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val deferredLoading = true
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "文件路径，使用当前会话工作区的读取后端。"),
        "action" to ToolParameter("action", ParameterType.STRING, "overview 通用信息；text 文本片段；archive 压缩包目录。", false, GeneralFileInspector.ACTIONS),
        "offset" to ToolParameter("offset", ParameterType.INTEGER, "按 next_offset 翻页；text 按字符计，archive 按匹配条目计。", false),
        "limit" to ToolParameter("limit", ParameterType.INTEGER, "1–100，默认 50；文本每单位最多 256 字符，总上限 16384 字符。", false),
        "query" to ToolParameter("query", ParameterType.STRING, "压缩包条目名称过滤，最长 256 字符。", false)
    )
    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        return try {
            val path = (args["path"] as? JsonPrimitive)?.contentOrNull ?: return ToolResult.Error("缺少文件路径", "MISSING_PATH")
            val action = (args["action"] as? JsonPrimitive)?.contentOrNull ?: "overview"
            val offset = args["offset"]?.let { (it as? JsonPrimitive)?.intOrNull ?: error("FILE_ANALYSIS_ARGS_INVALID") } ?: 0
            val limit = args["limit"]?.let { (it as? JsonPrimitive)?.intOrNull ?: error("FILE_ANALYSIS_ARGS_INVALID") } ?: 50
            val query = (args["query"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            require(action in GeneralFileInspector.ACTIONS && offset >= 0 && limit in 1..100 && query.length <= 256) { "FILE_ANALYSIS_ARGS_INVALID" }
            ToolResult.Success(service.inspectPath(context.projectRoot, path, action, offset, limit, query))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ToolResult.Error(failure.message ?: "文件分析失败", "FILE_ANALYSIS_FAILED") }
    }
}
