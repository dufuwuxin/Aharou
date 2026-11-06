package com.aharou.feature.agent.domain.subagent

import com.aharou.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentTeamParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val provider = TestFileAccessProvider()

    /** 解析器契约里的路径是容器路径（POSIX 分隔符），Windows 上要先归一化，理由同 AgentDefinitionParserTest。 */
    private fun parseFile(file: File): AgentTeam? =
        AgentTeamParser.parse(provider, file.absolutePath.replace(File.separatorChar, '/'))

    private fun write(name: String, content: String): File =
        File(tempFolder.root, name).apply { writeText(content) }

    @Test
    fun parse_builtinTeamsExposeRoleCardsAndMeetExecutionContract() {
        val root = File("src/main/assets/teams").takeIf { it.isDirectory }
            ?: File("app/src/main/assets/teams")
        val expected = mapOf(
            "feature-delivery" to ("Software Architect" to listOf(
                "Software Architect", "Android Kotlin Engineer", "Rust Engineer",
                "Python Backend Engineer", "Code Reviewer", "Reality Checker"
            )),
            "research-review" to ("Explore" to listOf(
                "Explore", "Trend Researcher", "Reality Checker", "Docs Writer"
            )),
            "ui-delivery" to ("UX Architect" to listOf(
                "UI Designer", "UX Architect", "Android Kotlin Engineer",
                "UI Finish-Gate Reviewer", "Accessibility Auditor"
            ))
        )
        for ((name, roster) in expected) {
            val team = requireNotNull(parseFile(File(root, "$name.md")))
            assertEquals(name, listOf(roster.first), team.admins)
            assertEquals(name, roster.second, team.members.map { it.name })
            assertTrue(name, team.members.all { it.level > 0 })
            assertTrue(name, team.stages.flatMap { it.tasks }.mapNotNull { it.agent }
                .all { it in roster.second })
            assertNull(name, TeamFlowGraph.validate(team))
            assertEquals("ONE_ADMIN_REQUIRED", TeamFlowGraph.validate(team.copy(admins = emptyList())))
            assertEquals("ONE_ADMIN_REQUIRED", TeamFlowGraph.validate(team.copy(admins = roster.second.take(2))))
            assertEquals("ADMIN_NOT_MEMBER", TeamFlowGraph.validate(team.copy(members =
                team.members.filterNot { it.name == roster.first })))
        }
    }

    @Test
    fun parse_singleAndParallelStages() {
        val file = write(
            "delivery.md",
            """
            ---
            name: 交付团队
            description: 拆解 → 实现 → 审查
            stages:
              - title: 拆解
                agent: Senior Project Manager
                prompt: 拆成子任务
              - title: 实现
                tasks:
                  - agent: Mobile App Builder
                    prompt: 实现界面
                    writePaths: [app/src/main/java/ui]
                  - department: engineering
                    agentQuery: api platform
                    prompt: 实现接口
            ---
            团队公约：不扩大范围。
            """.trimIndent()
        )

        val team = parseFile(file)!!

        assertEquals("交付团队", team.name)
        assertEquals("拆解 → 实现 → 审查", team.description)
        assertEquals("团队公约：不扩大范围。", team.charter)
        assertEquals(2, team.stages.size)
        assertEquals(3, team.taskCount)

        val first = team.stages[0]
        assertEquals("拆解", first.title)
        assertEquals("Senior Project Manager", first.tasks.single().agent)

        val parallel = team.stages[1]
        assertEquals(2, parallel.tasks.size)
        assertEquals(listOf("app/src/main/java/ui"), parallel.tasks[0].writePaths)
        assertEquals("engineering", parallel.tasks[1].department)
        assertEquals("api platform", parallel.tasks[1].agentQuery)
    }

    @Test
    fun parse_dropsTaskWithoutPrompt() {
        val file = write(
            "partial.md",
            """
            ---
            stages:
              - title: 实现
                tasks:
                  - agent: Code Reviewer
                    prompt: 审查改动
                  - agent: Docs Writer
                    prompt: "   "
            ---
            """.trimIndent()
        )

        val team = parseFile(file)!!

        val tasks = team.stages.single().tasks
        assertEquals(1, tasks.size)
        assertEquals("Code Reviewer", tasks.single().agent)
    }

    @Test
    fun parse_dropsStageWithoutValidTasks() {
        val file = write(
            "stage-drop.md",
            """
            ---
            stages:
              - title: 空阶段
                tasks:
                  - agent: Code Reviewer
                    prompt: 审查
              - title: 没人负责
                prompt: 没有 agent 也没有 department
            ---
            """.trimIndent()
        )

        val team = parseFile(file)!!

        assertEquals(1, team.stages.size)
        assertEquals("空阶段", team.stages.single().title)
    }

    @Test
    fun parse_returnsNullWithoutStages() {
        val file = write(
            "empty.md",
            """
            ---
            name: 没有阶段
            description: 只有描述
            ---
            团队公约。
            """.trimIndent()
        )

        assertNull(parseFile(file))
    }

    @Test
    fun parse_fallsBackToFileStemAsName() {
        val file = write(
            "no-name.md",
            """
            ---
            stages:
              - agent: Explore
                prompt: 查现状
            ---
            """.trimIndent()
        )

        val team = parseFile(file)!!

        assertEquals("no-name", team.name)
        assertTrue(team.charter.isEmpty())
    }

    @Test
    fun parse_readsTaskInterfaces() {
        val file = write(
            "interfaces.md",
            """
            ---
            stages:
              - title: 实现
                tasks:
                  - agent: 后端工程专家
                    prompt: 实现接口
                    interfaces:
                      - with: 前端工程专家
                        about: HTTP 响应字段
                      - 验收员
            ---
            """.trimIndent()
        )

        val team = parseFile(file)!!
        val interfaces = team.stages.single().tasks.single().interfaces

        assertEquals(2, interfaces.size)
        assertEquals("前端工程专家", interfaces[0].with)
        assertEquals("HTTP 响应字段", interfaces[0].about)
        assertEquals("验收员", interfaces[1].with)
        assertTrue(interfaces[1].about.isEmpty())
    }

    @Test
    fun serialize_roundTripsInterfacesThroughForm() {
        val form = AgentTeamForm(
            name = "交付",
            description = "",
            charter = "",
            stages = listOf(
                AgentTeamStageForm(
                    title = "实现",
                    tasks = listOf(
                        AgentTeamTaskForm(
                            agent = "后端工程专家",
                            prompt = "实现接口",
                            interfaces = listOf(
                                TeamInterface("前端工程专家", "HTTP 响应字段")
                            )
                        )
                    )
                )
            )
        )

        val text = AgentTeamParser.serialize(form)
        val file = write("roundtrip.md", text)
        val team = parseFile(file)!!
        val interfaces = team.stages.single().tasks.single().interfaces

        assertEquals(1, interfaces.size)
        assertEquals("前端工程专家", interfaces.single().with)
        assertEquals("HTTP 响应字段", interfaces.single().about)
    }
    @Test fun routeAndEndpointIdsSurviveEditAndSerializeRoundtrip() {
        val a = AgentTeamStageForm(title = "prepare", id = "s-a", tasks = listOf(AgentTeamTaskForm(agent = "worker", prompt = "deliver", id = "t-a")))
        val b = AgentTeamStageForm(title = "review", id = "s-b", isReview = true, tasks = listOf(AgentTeamTaskForm(agent = "worker", prompt = "verify", id = "t-b")))
        val routes = listOf(TeamRoute(TeamFlowNode("s-a", "t-a"), TeamFlowNode("s-b", "t-b"), TeamRouteAction.REVIEW),
            TeamRoute(TeamFlowNode("s-b", "t-b"), TeamFlowNode("s-a", "t-a"), TeamRouteAction.REJECT))
        val form = AgentTeamForm(name = "roundtrip", admins = listOf("worker"), members = listOf(TeamMemberRef("worker")),
            stages = listOf(a, b), routes = routes)
        val parsed = parseFile(write("roundtrip.md", AgentTeamParser.serialize(form)))!!
        assertEquals(routes, parsed.routes)
        val renamed = parsed.toForm(AgentDefinitionScope.GLOBAL).let { it.copy(stages = it.stages.map { stage -> stage.copy(title = stage.title + " renamed") }) }
        val again = parseFile(write("again.md", AgentTeamParser.serialize(renamed)))!!
        assertEquals(routes, again.routes)
        assertEquals(listOf("s-a", "s-b"), again.stages.map { it.id })
        assertEquals(listOf("t-a", "t-b"), again.stages.flatMap { it.tasks }.map { it.id })
    }
    @Test fun editingStageMetadataPreservesDepartmentAndMixedTasks() {
        val department = AgentTeamTaskForm(department = "engineering", agentQuery = "api", prompt = "original", writePaths = "src/api", interfaces = listOf(TeamInterface("worker", "contract")), id = "department-task")
        for (named in listOf(emptyList(), listOf(AgentTeamTaskForm(agent = "worker", prompt = "original", id = "named-task")))) {
            val stage = AgentTeamStageForm(title = "old", id = "stable-stage", prompt = "original", members = named.map { it.agent }, tasks = named + department)
            val changed = stage.copy(title = "renamed", messageLimit = 19).withFlowTasks()
            assertEquals(department, changed.tasks.first { it.id == department.id })
            val form = AgentTeamForm(name = "preserve", stages = listOf(changed), admins = listOf("worker"), members = listOf(TeamMemberRef("worker")))
            val parsed = parseFile(write("preserve.md", AgentTeamParser.serialize(form)))!!
            val actual = parsed.stages.single().tasks.first { it.id == department.id }
            assertEquals("engineering", actual.department)
            assertEquals("api", actual.agentQuery)
            assertEquals(listOf("src/api"), actual.writePaths)
            assertEquals(department.interfaces, actual.interfaces)
        }
    }
}
