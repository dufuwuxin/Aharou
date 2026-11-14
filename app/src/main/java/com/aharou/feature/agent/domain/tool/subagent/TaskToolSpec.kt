package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter

internal object TaskToolSpec {
    val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action", type = ParameterType.STRING,
            description = "teams 查询团队（主代理管理员可见完整详情）；team_run 执行；team_assign 管理模式改派已有任务，下次运行生效。",
            required = true, enum = listOf("teams", "team_run", "team_assign")
        ),
        "team" to ToolParameter(
            name = "team", type = ParameterType.STRING,
            description = "团队名称，来自 teams 返回的团队介绍；team_run 必填。",
            required = false
        ),
        "stageId" to ToolParameter(name = "stageId", type = ParameterType.STRING,
            description = "team_assign 必填：完整详情中的已有阶段 id。", required = false),
        "taskId" to ToolParameter(name = "taskId", type = ParameterType.STRING,
            description = "team_assign 必填：该阶段内已有任务 id。", required = false),
        "member" to ToolParameter(name = "member", type = ParameterType.STRING,
            description = "team_assign 必填：同一团队名单内的真实成员名。不接受主代理保留值。", required = false),
        "goal" to ToolParameter(
            name = "goal", type = ParameterType.STRING,
            description = "交给团队完成的目标；team_run 必填。成员分工和内部交接由团队执行。",
            required = false
        )
    )
}
