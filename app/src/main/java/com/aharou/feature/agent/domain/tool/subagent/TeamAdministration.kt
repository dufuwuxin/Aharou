package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamForm
import com.aharou.feature.agent.domain.subagent.TeamFlowGraph
import com.aharou.feature.agent.domain.subagent.toForm
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The caller's identity is checked by TaskTool against the persisted session, never tool arguments. */
internal object TeamAdministration {
    fun detail(team: AgentTeam, mainSession: Boolean): JsonObject = buildJsonObject {
        put("name", team.name)
        put("description", team.description)
        put("charter", team.charter)
        if (mainSession && team.mainAgentAdmin) {
            put("admins", buildJsonArray { team.admins.forEach { add(JsonPrimitive(it)) } })
            put("members", buildJsonArray { team.members.forEach { member -> add(buildJsonObject {
                put("name", member.name); put("level", member.level)
                put("skills", buildJsonArray { member.skills.forEach { add(JsonPrimitive(it)) } })
            }) } })
            put("stages", buildJsonArray { team.stages.forEach { stage -> add(buildJsonObject {
                put("id", stage.id); put("title", stage.title); put("level", stage.level)
                put("review", stage.isReview); put("rejectTo", JsonPrimitive(stage.rejectTo))
                put("communication", stage.communication.token); put("messageLimit", stage.messageLimit)
                put("communicationGuide", stage.communicationGuide)
                put("tasks", buildJsonArray { stage.tasks.forEach { task -> add(buildJsonObject {
                    put("id", task.id); put("agent", JsonPrimitive(task.agent))
                    put("department", JsonPrimitive(task.department))
                    put("agentQuery", JsonPrimitive(task.agentQuery)); put("prompt", task.prompt)
                    put("writePaths", buildJsonArray { task.writePaths.forEach { add(JsonPrimitive(it)) } })
                    put("interfaces", buildJsonArray { task.interfaces.forEach { edge -> add(buildJsonObject {
                        put("with", edge.with); put("about", edge.about)
                    }) } })
                }) } })
            }) } })
            put("routes", buildJsonArray { team.routes.forEach { route -> add(buildJsonObject {
                put("fromStage", route.from.stageId); put("fromTask", route.from.taskId)
                put("toStage", route.to.stageId); put("toTask", route.to.taskId); put("action", route.action.name)
            }) } })
        }
    }

    /** Rebind one existing task only; no new prompt, task, member, route or write authority. */
    fun assign(entry: AgentTeamEntry, stageId: String, taskId: String, member: String): AgentTeamForm? {
        val team = entry.team
        if (!team.mainAgentAdmin || TeamFlowGraph.validate(team) != null) return null
        val chosen = team.members.singleOrNull { it.name.equals(member.trim(), ignoreCase = true) } ?: return null
        if (team.stages.count { it.id == stageId } != 1) return null
        val stage = team.stages.single { it.id == stageId }
        if (stage.tasks.count { it.id == taskId } != 1) return null
        // The editor represents a named member once per stage; do not merge two tasks on save.
        if (stage.tasks.any { it.id != taskId && it.agent.equals(chosen.name, ignoreCase = true) }) return null
        val form = team.toForm(entry.scope)
        return form.copy(stages = form.stages.map { item ->
            val tasks = if (item.id == stageId) item.tasks.map { task ->
                if (task.id == taskId) task.copy(agent = chosen.name, department = "", agentQuery = "") else task
            } else item.tasks
            item.copy(prompt = "", tasks = tasks,
                members = tasks.map { it.agent }.filter { it.isNotBlank() }.distinct(),
                interfaces = tasks.flatMap { task -> task.interfaces.map { edge ->
                    com.aharou.feature.agent.domain.subagent.TeamInterfaceEdgeForm(task.agent, edge.with, edge.about)
                } })
        })
    }
}
