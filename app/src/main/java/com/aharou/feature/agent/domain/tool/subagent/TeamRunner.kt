package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.SubAgentProfileMatcher
import com.aharou.feature.agent.domain.subagent.TeamStage
import com.aharou.feature.agent.domain.subagent.TeamTask
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * 团队预设的展开与文案：把 `stages` 变成 `task(action="pipeline")` 能直接跑的数组。
 *
 * 抽成纯逻辑有两个理由：单文件行数门禁盯着 TaskTool；这里的校验与格式化可以在单测里直接覆盖，
 * 不必真的派子代理。
 */
object TeamRunner {

    private const val DESCRIPTION_MAX = 30
    private const val LIST_DESCRIPTION_MAX = 160

    /** 展开结果：要么是可执行的 pipeline，要么是带错误码的拒绝原因。 */
    sealed interface Plan {
        /** [layers] 是实际执行层数：阶段内任务超过并发上限会被拆成多批，比阶段数多。 */
        data class Ready(val pipeline: JsonArray, val layers: Int, val taskCount: Int) : Plan
        data class Rejected(val code: String, val message: String) : Plan
    }

    /**
     * 派发前把角色引用一次性查完：写错角色名会让前面几个子代理白烧 token。
     *
     * 阶段内任务数超过并发上限时**自动分批排队**（后一批依赖前一批），不报错——预设里多写两个
     * 成员不该逼用户去拆阶段。
     */
    fun build(team: AgentTeam, goal: String, enabled: List<AgentDefinition>, maxRunning: Int): Plan {
        val badRoles = mutableListOf<String>()
        team.stages.forEach { stage ->
            stage.tasks.forEach { task ->
                val agentName = task.agent
                if (agentName != null) {
                    if (enabled.none { it.name.equals(agentName, ignoreCase = true) }) {
                        badRoles += "${stage.title} → $agentName"
                    }
                } else if (
                    SubAgentProfileMatcher.match(enabled, task.department, task.agentQuery)
                    is SubAgentProfileMatcher.MatchOutcome.Unmatched
                ) {
                    badRoles += "${stage.title} → ${task.memberLabel()}"
                }
            }
        }
        if (badRoles.isNotEmpty()) {
            return Plan.Rejected(
                "AGENT_NOT_FOUND",
                "团队「${team.name}」引用了不可用的角色：${badRoles.joinToString("；")}。" +
                    "可用部门：${SubAgentProfileMatcher.departmentHint(enabled)}；" +
                    "先用 task(action=\"roles\", department=\"…\", agentQuery=\"…\") 查角色名，再改团队文件的 agent 字段。"
            )
        }

        val batchSize = maxRunning.coerceAtLeast(1)
        var layers = 0
        val pipeline = buildJsonArray {
            var upstream: List<String> = emptyList()
            team.stages.forEachIndexed { stageIndex, stage ->
                stage.tasks.chunked(batchSize).forEachIndexed { batchIndex, batch ->
                    val ids = batch.indices.map { "s${stageIndex + 1}-b${batchIndex + 1}-t${it + 1}" }
                    batch.forEachIndexed { taskIndex, task ->
                        addJsonObject {
                            put("id", ids[taskIndex])
                            put("description", "${stage.title}·${task.memberLabel()}".take(DESCRIPTION_MAX))
                            put("prompt", taskPrompt(team, stage, task, goal))
                            task.agent?.let { put("agent", it) }
                            task.department?.let { put("department", it) }
                            task.agentQuery?.let { put("agentQuery", it) }
                            if (task.writePaths.isNotEmpty()) {
                                put("writePaths", buildJsonArray { task.writePaths.forEach { add(JsonPrimitive(it)) } })
                            }
                            if (upstream.isNotEmpty()) {
                                put("dependsOn", buildJsonArray { upstream.forEach { add(JsonPrimitive(it)) } })
                            }
                        }
                    }
                    upstream = ids
                    layers++
                }
            }
        }
        return Plan.Ready(pipeline, layers, team.taskCount)
    }

    /** 全部团队的一行式清单。 */
    fun renderList(entries: List<AgentTeamEntry>): String = buildString {
        append("团队（共 ").append(entries.size).append(" 个）：\n")
        entries.forEach { entry ->
            val team = entry.team
            append("- ").append(team.name).append("（").append(scopeLabel(entry.scope)).append("，")
                .append(team.members.size).append(" 成员 / ").append(team.admins.size).append(" 管理员 / ")
                .append(team.stages.size).append(" 阶段 / ").append(team.taskCount).append(" 任务）")
            val summary = team.description.lineSequence().first().trim().take(LIST_DESCRIPTION_MAX)
            if (summary.isNotEmpty()) append("：").append(summary)
            append("\n  流程：").append(team.stages.joinToString(" → ") { it.title }).append("\n")
            if (team.rejectEdges.isNotEmpty()) {
                append("  驳回边：")
                    .append(team.rejectEdges.joinToString("；") { (from, to) -> "$from ⇠ $to" })
                    .append("\n")
            }
        }
        append("执行：task(action=\"team_run\", team=\"<名>\", goal=\"<本次目标>\"）；")
            .append("看单个团队的名单与流程用 task(action=\"teams\", team=\"<名>\")。")
    }

    /** 单个团队的名单、层级、管理员与流程详情。 */
    fun renderDetail(entry: AgentTeamEntry): String = buildString {
        val team = entry.team
        append("团队「").append(team.name).append("」（").append(scopeLabel(entry.scope)).append("）")
        if (team.description.isNotBlank()) append("：").append(team.description)
        if (team.admins.isNotEmpty()) {
            append("\n管理员组：").append(team.admins.joinToString("、"))
            if (team.admins.size > 1) append("（并行思考，结论由主代理汇总成策略建议）")
        }
        if (team.members.isNotEmpty()) {
            append("\n成员名单：")
            team.levels.forEach { level ->
                val names = team.members.filter { it.level == level }.map { it.name }
                if (names.isNotEmpty()) {
                    append("\n  ").append(levelLabel(level)).append("：").append(names.joinToString("、"))
                }
            }
        }
        append("\n共 ").append(team.stages.size).append(" 个阶段、").append(team.taskCount).append(" 个任务：\n")
        team.stages.forEachIndexed { index, stage ->
            append(index + 1).append(". ").append(stage.title)
                .append("（").append(levelLabel(stage.level))
                .append(if (stage.tasks.size > 1) "，并行 ${stage.tasks.size} 个" else "，1 个")
                .append(if (stage.isReview) "，关卡" else "").append("）：")
            append(stage.tasks.joinToString("、") { it.memberLabel() })
            stage.rejectTo?.let { append("；不通过则驳回→").append(it) }
            append("\n")
        }
        // 团队公约不在主代理可见层：它属于子代理指令的一部分，只在 team_run 展开时随 task prompt 注入。
        append("文件：").append(team.filePath ?: "-").append("\n")
        append("执行：task(action=\"team_run\", team=\"").append(team.name).append("\", goal=\"<本次目标>\")")
    }

    fun levelLabel(level: Int): String = when (level) {
        1 -> "一级成员组"
        2 -> "二级成员组"
        3 -> "三级成员组"
        else -> "${level}级成员组"
    }

    fun scopeLabel(scope: AgentDefinitionScope): String =
        if (scope == AgentDefinitionScope.GLOBAL) "全局" else "项目"

    /** 单个团队成员的任务指令：团队公约 + 本次目标 + 本阶段任务 + 关系链（执行/驳回）。 */
    private fun taskPrompt(team: AgentTeam, stage: TeamStage, task: TeamTask, goal: String): String =
        buildString {
            append("你是团队「").append(team.name).append("」的").append(levelLabel(stage.level))
            append("成员，现在处于阶段「").append(stage.title).append("」。\n")
            append("关系链：执行——接下分配下来的内容并完成，产出交回主代理。\n")
            append("本次团队目标：").append(goal).append("\n")
            if (team.charter.isNotBlank()) append("\n团队公约：\n").append(team.charter).append("\n")
            if (task.agent != null && task.agent in team.admins) {
                append("你是本团队管理员之一：先独立给出判断，不要预设其他管理员会怎么想。\n")
            }
            append("\n你的任务：\n").append(task.prompt)
            if (stage.isReview) {
                append("\n\n本阶段是关卡。逐条核对验收标准后，结论首行必须是：\n")
                append("- 通过：写「通过」，然后列出验收依据；\n")
                if (stage.rejectTo != null) {
                    append("- 不通过：写「驳回→").append(stage.rejectTo)
                        .append("」，再逐条列出必改点（谁改、改什么），交回主代理重排。\n")
                } else {
                    append("- 不通过：写「驳回」，列出必改点（本关是终审，没有可回退的阶段）。\n")
                }
            }
        }

    private fun TeamTask.memberLabel(): String =
        agent ?: listOfNotNull(department, agentQuery).joinToString("/")
}
