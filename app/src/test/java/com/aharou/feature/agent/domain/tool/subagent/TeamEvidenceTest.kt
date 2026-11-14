package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TeamEvidenceTest {
    private fun evidence(run: String, acceptance: String, failures: String = "[]", exit: Int = 0) =
        "TEAM_EVIDENCE: " + """{"schema_version":1,"status":"done","acceptance_id":"$acceptance","baseline":{"run_id":"$run","source_revision":"fixture"},"evidence_refs":[{"id":"proof","kind":"command","ref":"fixture-log:1","sha256":"${"a".repeat(64)}","command":"fixture","exit_code":$exit,"observation":"actual fixture output"}],"failures":$failures,"unverified":[]}"""
    @Test fun missingFailureChangedAcceptanceAndWrongRunAreRejected() {
        assertNotNull(TeamEvidence.error("done", "r", "a"))
        assertNull(TeamEvidence.error(evidence("r", "a"), "r", "a"))
        assertNotNull(TeamEvidence.error(evidence("wrong", "a"), "r", "a"))
        assertNotNull(TeamEvidence.error(evidence("r", "new-standard"), "r", "a"))
        assertNotNull(TeamEvidence.error(evidence("r", "a", "[{}]"), "r", "a"))
        assertNotNull(TeamEvidence.error(evidence("r", "a", exit = 1), "r", "a"))
    }
    @Test fun actualStageExecutorBlocksFakeDoneAndAcceptsRunBoundReceipts() = runBlocking {
        val role = AgentDefinition(name = "worker", description = "fixture", prompt = "fixture")
        val team = AgentTeam("fixture", "", listOf(TeamStage("work", listOf(TeamTask(agent = "worker", prompt = "fixture")))),
            "", admins = listOf("worker"), members = listOf(TeamMemberRef("worker")), evidenceRequired = true)
        suspend fun execute(valid: Boolean): ToolResult = TeamStageExecutor(TeamRunRegistry()).run(team, "fixture", listOf(role), 1, "parent") { batch ->
            ToolResult.Success(buildJsonObject {
                put("state", "done")
                put("outputs", buildJsonObject { batch.forEach {
                    val task = it.jsonObject; val id = task.getValue("id").jsonPrimitive.content
                    val run = task.getValue("_teamRunId").jsonPrimitive.content
                    put(id, if (valid) evidence(run, "fixture:$id") else "Everything was completed")
                } })
            })
        }
        assertEquals("blocked", (execute(false) as ToolResult.Success).data.jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("done", (execute(true) as ToolResult.Success).data.jsonObject.getValue("state").jsonPrimitive.content)
    }
}
