package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.subagent.MaxRunningSource
import com.aharou.feature.agent.domain.tool.ToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 团队预设的两个工具动作：`teams`（列清单/详情）与 `team_run`（按预设跑）。
 *
 * 抽在 TaskTool 外面是为了让它保持入口职责：这里负责读预设、查角色、按并发上限分批展开，再把
 * pipeline 交给调用方执行——执行仍走 TaskTool 的 `runPipeline`，分层调度与写冲突拦截不重复实现。
 */
@Singleton
class TeamTasks @Inject constructor(
    private val teamRepository: AgentTeamRepository,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val maxRunningSource: MaxRunningSource,
    private val stageExecutor: TeamStageExecutor
) {

    fun list(args: Map<String, JsonElement>, context: AgentContext, mainSession: Boolean = false): ToolResult {
        val entries = loadEntries(context.projectRoot) ?: return ToolResult.Error("读取团队预设失败", "TEAM_LIST_FAILED")
        val target = (args["team"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (target.isNotEmpty()) {
            val entry = entries.firstOrNull { it.team.name.equals(target, ignoreCase = true) }
                ?: return ToolResult.Error(notFoundMessage(entries, target), "TEAM_NOT_FOUND")
            return ToolResult.Success(TeamAdministration.detail(entry.team, mainSession))
        }
        if (entries.isEmpty()) {
            return ToolResult.Error(
                "当前没有可用团队，请先在配置页面创建团队。",
                "TEAM_LIST_EMPTY"
            )
        }
        return ToolResult.Success(
            buildJsonObject {
                put("teams", buildJsonArray {
                    entries.forEach { entry -> add(buildJsonObject {
                        put("name", entry.team.name)
                        put("description", entry.team.description)
                        put("charter", entry.team.charter)
                    }) }
                })
                put("count", entries.size)
            }
        )
    }

    /**
     * 按预设展开并执行。[exec] 接收展开好的 pipeline 数组，由调用方交给 runPipeline 跑。
     */
    suspend fun run(
        args: Map<String, JsonElement>,
        parentSessionId: String,
        workspace: String,
        expectedSignature: String? = null,
        exec: suspend (JsonArray) -> ToolResult
    ): ToolResult {
        val teamName = (args["team"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (teamName.isEmpty()) {
            return ToolResult.Error("team_run 需要 team（团队名，先用 task(action=\"teams\") 查）", "MISSING_TEAM")
        }
        val goal = (args["goal"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (goal.isEmpty()) {
            return ToolResult.Error("team_run 需要 goal（本次团队要达成的目标）", "MISSING_GOAL")
        }

        val entries = loadEntries(workspace) ?: return ToolResult.Error("读取团队预设失败", "TEAM_LIST_FAILED")
        val team = entries.firstOrNull { it.team.name.equals(teamName, ignoreCase = true) }?.team
            ?: return ToolResult.Error(notFoundMessage(entries, teamName), "TEAM_NOT_FOUND")

        val enabled: List<AgentDefinition> = try {
            agentDefinitionRepository.listEnabled().map { it.definition }
        } catch (e: Exception) {
            FileLogger.e(TAG, "读取子代理定义失败", e)
            return ToolResult.Error("读取子代理定义失败：${e.message ?: "未知错误"}", "AGENT_LIST_FAILED")
        }

        if (expectedSignature != null && expectedSignature != signature(team, enabled))
            return ToolResult.Error("团队配置已变化，请重新执行", "TEAM_CONFIG_CHANGED")
        return when (val plan = TeamRunner.build(team, goal, enabled, maxRunningSource.maxRunning())) {
            is TeamRunner.Plan.Rejected -> ToolResult.Error(plan.message, plan.code)
            is TeamRunner.Plan.Ready -> {
                FileLogger.i(
                    TAG,
                    "团队「${team.name}」开始执行：${team.stages.size} 阶段 → ${plan.layers} 层 / ${plan.taskCount} 任务"
                )
                stageExecutor.run(team, goal, enabled, maxRunningSource.maxRunning(), parentSessionId, exec)
            }
        }
    }

    fun assign(args: Map<String, JsonElement>, context: AgentContext, mainSession: Boolean): ToolResult {
        if (!mainSession) return ToolResult.Error("仅主会话可管理团队", "TEAM_ADMIN_DENIED")
        fun text(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val repo = teamRepository.forWorkspace(context.projectRoot)
        val entry = repo.listAll().firstOrNull { it.team.name.equals(text("team"), ignoreCase = true) }
            ?: return ToolResult.Error("团队不存在", "TEAM_NOT_FOUND")
        if (!entry.team.mainAgentAdmin) return ToolResult.Error("该团队不由主代理管理", "TEAM_ADMIN_DENIED")
        val form = TeamAdministration.assign(entry, text("stageId"), text("taskId"), text("member"))
            ?: return ToolResult.Error("仅可将现有任务分配给该团队名单内成员，且不能合并任务", "INVALID_TEAM_ASSIGNMENT")
        val error = repo.save(form, entry.team.name)
        if (error != null) return ToolResult.Error("团队分配未保存：$error", "TEAM_ASSIGN_FAILED")
        return ToolResult.Success(buildJsonObject {
            put("team", entry.team.name); put("state", "assigned"); put("appliesTo", "next_run")
        })
    }

    internal fun selectProjectTeam(teamName: String?, goal: String, workspace: String): ToolResult {
        val entries = loadEntries(workspace) ?: return ToolResult.Error("读取团队预设失败", "TEAM_LIST_FAILED")
        val enabled = try { agentDefinitionRepository.listEnabled().map { it.definition } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return ToolResult.Error("读取成员定义失败", "AGENT_LIST_FAILED") }
        val requested = teamName?.trim().orEmpty().takeUnless { it.equals("auto", true) }.orEmpty()
        val candidates = if (requested.isBlank()) entries.sortedBy { it.team.name } else
            entries.filter { it.team.name.equals(requested, true) }
        val selected = candidates.firstOrNull {
            com.aharou.feature.agent.domain.subagent.TeamFlowGraph.validate(it.team) == null &&
                (TeamRunner.build(it.team, goal, enabled, maxRunningSource.maxRunning()) as? TeamRunner.Plan.Ready)?.taskCount?.let { count -> count > 0 } == true
        } ?: return ToolResult.Error(if (requested.isBlank()) "没有可运行团队，请先配置团队" else "指定团队不存在或不可运行：$requested", "PROJECT_TEAM_UNAVAILABLE")
        return ToolResult.Success(buildJsonObject {
            put("team", selected.team.name)
            put("signature", signature(selected.team, enabled))
            put("selectionReason", if (requested.isBlank()) "auto:first-buildable-by-name" else "explicit:exact-name")
        })
    }

    private fun signature(team: com.aharou.feature.agent.domain.subagent.AgentTeam, enabled: List<AgentDefinition>): String =
        java.security.MessageDigest.getInstance("SHA-256").digest((team.toString() + enabled.toString() + maxRunningSource.maxRunning()).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun loadEntries(workspace: String): List<AgentTeamEntry>? = try {
        teamRepository.forWorkspace(workspace).listAll()
    } catch (e: Exception) {
        FileLogger.e(TAG, "读取团队预设失败", e)
        null
    }

    private fun notFoundMessage(entries: List<AgentTeamEntry>, target: String): String =
        if (entries.isEmpty()) {
            "团队预设不存在：$target；当前也没有任何预设。在 ${teamRepository.globalRoot} 下新建 <名>.md 即可。"
        } else {
            "团队预设不存在：$target。可用团队：${entries.joinToString("、") { it.team.name }}"
        }

    private companion object {
        const val TAG = "TeamTasks"
    }
}
