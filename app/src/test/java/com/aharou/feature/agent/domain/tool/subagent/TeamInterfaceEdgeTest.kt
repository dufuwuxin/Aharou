package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.TeamInterface
import com.aharou.feature.agent.domain.subagent.TeamStage
import com.aharou.feature.agent.domain.subagent.TeamTask
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接口边声明：展开时必须双向出现在相关成员的 prompt 里，解析不到的目标不许静默丢弃。 */
class TeamInterfaceEdgeTest {

    private val enabled = listOf(
        AgentDefinition(name = "后端工程专家", description = "后端", prompt = "p"),
        AgentDefinition(name = "Python 工程师", description = "py", prompt = "p"),
        AgentDefinition(name = "验收员", description = "验收", prompt = "p")
    )

    private fun team(backendInterfaces: List<TeamInterface> = emptyList(),
        pythonInterfaces: List<TeamInterface> = emptyList()) = AgentTeam(
        name = "交付",
        description = "",
        stages = listOf(
            TeamStage(
                "并行实现",
                listOf(
                    TeamTask(
                        agent = "后端工程专家", prompt = "实现接口",
                        interfaces = backendInterfaces
                    ),
                    TeamTask(
                        agent = "Python 工程师", prompt = "实现脚本",
                        interfaces = pythonInterfaces
                    )
                )
            ),
            TeamStage("验收", listOf(TeamTask(agent = "验收员", prompt = "逐条核对")))
        ),
        charter = ""
    )

    private fun build(team: AgentTeam): Map<String, String> {
        val plan = TeamRunner.build(team, "把登录改造完", enabled, 5) as TeamRunner.Plan.Ready
        return plan.pipeline.filterIsInstance<JsonObject>().associate {
            it.getValue("id").let { id -> id.jsonPrimitive.content } to
                (it["prompt"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
    }

    @Test fun outboundEdgeResolvesPeerToLogicalIdAndKeepsAboutText() {
        val prompts = build(team(listOf(TeamInterface("Python 工程师", "HTTP 响应字段"))))
        val backend = prompts.getValue("s1-b1-t1")
        assertTrue(backend.contains("你的接口边"))
        assertTrue(backend.contains("→ 任务 s1-b1-t2"))
        assertTrue(backend.contains("HTTP 响应字段"))
    }

    @Test fun inboundEdgeAppearsOnDeclaredTargetWithoutDeclaringIt() {
        // 边由 Python 声明指向后端：后端自己没写任何声明，也必须看到「有人在等你对齐」。
        val prompts = build(team(pythonInterfaces = listOf(TeamInterface("后端工程专家", "路由前缀"))))
        val backend = prompts.getValue("s1-b1-t1")
        assertTrue(backend.contains("← s1-b1-t2"))
        assertTrue(backend.contains("路由前缀"))
        assertFalse(backend.contains("→ 任务"))
        val python = prompts.getValue("s1-b1-t2")
        assertTrue(python.contains("→ 任务 s1-b1-t1"))
    }

    @Test fun unresolvedTargetIsKeptVisibleInsteadOfDropped() {
        val prompts = build(team(listOf(TeamInterface("不存在的成员"))))
        assertTrue(prompts.getValue("s1-b1-t1").contains("「不存在的成员」（未解析"))
    }

    @Test fun teamWithoutInterfacesInjectsNoEdgeSection() {
        val prompts = build(team(emptyList()))
        assertFalse(prompts.values.any { it.contains("你的接口边") })
    }

    @Test fun crossStageTargetResolvesByAgentName() {
        val withReview = AgentTeam(
            name = "交付",
            description = "",
            stages = listOf(
                TeamStage(
                    "并行实现",
                    listOf(
                        TeamTask(
                            agent = "后端工程专家", prompt = "实现接口",
                            interfaces = listOf(TeamInterface("验收员", "验收口径"))
                        ),
                        TeamTask(agent = "Python 工程师", prompt = "实现脚本")
                    )
                ),
                TeamStage("验收", listOf(TeamTask(agent = "验收员", prompt = "逐条核对")))
            ),
            charter = ""
        )
        val prompts = build(withReview)
        assertTrue(prompts.getValue("s1-b1-t1").contains("→ 任务 s2-b1-t1"))
        // 反向也要出现在验收员自己的 prompt 里。
        assertTrue(prompts.getValue("s2-b1-t1").contains("← s1-b1-t1"))
    }

    @Test fun edgeSectionSitsInsidePromptNotAsSeparateField() {
        val prompts = build(team(listOf(TeamInterface("Python 工程师", ""))))
        assertTrue(prompts.getValue("s1-b1-t1").contains("## 你的接口边"))
        // about 为空时不能留下悬空的冒号。
        assertFalse(Regex("→ 任务 s1-b1-t2：").containsMatchIn(prompts.getValue("s1-b1-t1")))
    }
}
