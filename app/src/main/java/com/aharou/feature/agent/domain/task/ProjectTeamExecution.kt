package com.aharou.feature.agent.domain.task

import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Project receipt validation around the existing team runner; never creates members itself. */
internal object ProjectTeamExecution {
    data class Selection(val name: String, val signature: String, val reason: String) {
        val participant: String get() = "team:$name:$signature"
    }
    fun usesTeam(project: ProjectPlan, task: ProjectTask): Boolean =
        project.executionTeam != null || ProjectWorkflowPlan.participants(task).isEmpty()

    fun selection(result: ToolResult): Selection {
        val data = data(result)
        return Selection(field(data, "team"), field(data, "signature"), field(data, "selectionReason"))
    }
    private fun data(result: ToolResult): JsonObject {
        if (result is ToolResult.Error) error("${result.code}: ${result.message}")
        return (result as? ToolResult.Success)?.data as? JsonObject ?: error("PROJECT_TEAM_RECEIPT_MISSING")
    }
    private fun field(data: JsonObject, key: String): String =
        (data[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("PROJECT_TEAM_${key.uppercase()}_MISSING")

    fun receipt(result: ToolResult, selected: Selection, version: Long): Pair<String, String> {
        val data = data(result)
        check(field(data, "state") == "done") { "PROJECT_TEAM_BLOCKED: ${(data["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()}" }
        val runId = field(data, "runId")
        val outputs = data["outputs"] as? JsonObject ?: error("PROJECT_TEAM_OUTPUTS_MISSING")
        check(outputs.isNotEmpty() && outputs.values.all { (it as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true }) { "PROJECT_TEAM_OUTPUTS_MISSING" }
        return "project-team:${selected.participant}:v$version:run:$runId" to
            "Team: ${selected.name}\nSelection: ${selected.reason}\nRun: $runId\nRequirements: $version\n" +
                outputs.entries.joinToString("\n\n") { (id, text) -> "[$id]\n${text.jsonPrimitive.content}" }
    }

    suspend fun execute(timeoutMs: Long, current: suspend () -> Unit, invoke: suspend () -> ToolResult): ToolResult =
        withTimeout(timeoutMs) {
            coroutineScope {
                val running = async { invoke() }
                while (!running.isCompleted) { current(); delay(100) }
                current()
                running.await()
            }
        }
}
