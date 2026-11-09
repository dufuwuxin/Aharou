package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter

internal object TaskToolSpec {
    val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action", type = ParameterType.STRING,
            description = "teams 查看团队名称与介绍；team_run 向指定团队分配任务。",
            required = true, enum = listOf("teams", "team_run")
        ),
        "team" to ToolParameter(
            name = "team", type = ParameterType.STRING,
            description = "团队名称，来自 teams 返回的团队介绍；team_run 必填。",
            required = false
        ),
        "goal" to ToolParameter(
            name = "goal", type = ParameterType.STRING,
            description = "交给团队完成的目标；team_run 必填。成员分工和内部交接由团队执行。",
            required = false
        )
    )
}
