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
            description = "旧版 team_run 主题；单独使用时传给入口阶段成员；与新字段混用时仅作单个入口任务参考。新调用用 sharedGoal，不复制用户原文。",
            required = false
        ),
        "sharedGoal" to ToolParameter(
            name = "sharedGoal", type = ParameterType.STRING, required = false,
            description = "新 team_run 调用必填：简短、明确、可共享的团队目标与共同约束；不要粘贴原始需求。"
        ),
        "taskBriefs" to ToolParameter(
            name = "taskBriefs", type = ParameterType.ARRAY, required = false,
            description = "team_run：主代理管理的团队可按 teams(team=…) 详情为已有步骤提供专属简述；普通团队省略此参数，仅传 sharedGoal，不须查看内部步骤。",
            itemsSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "stageId" to mapOf("type" to "string"),
                    "taskId" to mapOf("type" to "string"),
                    "brief" to mapOf("type" to "string", "description" to "只有此步骤需要的任务、范围和验收，不复制整段请求")
                ),
                "required" to listOf("stageId", "taskId", "brief"),
                "additionalProperties" to false
            )
        )
    )
}
