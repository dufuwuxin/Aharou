package com.aharou.feature.agent.domain.tool.explorer

import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class CodeStructureTool @Inject constructor(
    private val commandEngine: CommandEngine
) : AbstractContextualTool() {
    override val name = "code_structure"
    override val description = "Check Kotlin/Rust/Python lexical issues or query bounded symbols, a repo map or lexical caller candidates. " +
        "Uses a fresh workspace snapshot, not a semantic call graph. Never runs project code or installs tools. " +
        "Partial/truncated results are explicit; use existing search/readFile for details."
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val deferredLoading = true
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "Query kind", required = true,
            enum = listOf("status", "check", "symbols", "map", "callers")),
        "path" to ToolParameter("path", ParameterType.STRING, "Path relative to the session workspace; default .", required = false),
        "name" to ToolParameter("name", ParameterType.STRING, "Symbol filter; required for callers", required = false),
        "limit" to ToolParameter("limit", ParameterType.INTEGER, "Maximum records, 1..200; default 80", required = false)
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull
        if (action !in setOf("status", "check", "symbols", "map", "callers")) {
            return ToolResult.Error("Use status, check, symbols, map or callers", "INVALID_ACTION")
        }
        val path = (args["path"] as? JsonPrimitive)?.contentOrNull ?: "."
        val name = (args["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (action == "callers" && name.isBlank()) return ToolResult.Error("Symbol name is required", "NAME_REQUIRED")
        val limit = (args["limit"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 200) ?: 80
        if (listOf(path, name).any { '\u0000' in it }) return ToolResult.Error("Invalid argument", "INVALID_ARGUMENT")
        if (context.projectRoot.isBlank()) return ToolResult.Error("Workspace is required", "MISSING_WORKSPACE")
        val command = "python3 -I \"\$HOME/.aharou/lib/codekit/query.py\" $action --path ${quote(path)} --name ${quote(name)} --limit $limit"
        return try {
            val captured = commandEngine.runCommandSyncIfReady(command, context.projectRoot, 15_000L)
            currentCoroutineContext().ensureActive()
            val result = captured ?: return ToolResult.Error("Execution backend is not ready; initialize it in the terminal. Remote hosts need the packaged helper installed explicitly.", "CODE_STRUCTURE_UNAVAILABLE")
            if (result.outputTruncated) return ToolResult.Error("Code query transport was truncated", "CODE_STRUCTURE_TRUNCATED")
            if (result.exitCode != 0) return ToolResult.Error(result.output.take(2000), "CODE_STRUCTURE_FAILED")
            ToolResult.Success(Json.parseToJsonElement(result.output.trim()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Error(e.message ?: "Code structure query failed", "CODE_STRUCTURE_FAILED")
        }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}
