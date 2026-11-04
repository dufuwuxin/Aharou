package com.aharou.feature.agent.domain.subagent

import com.aharou.feature.workspace.domain.FileAccessProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 阶段级接口边（编辑页「协作对齐」）的保存与回填回归：
 * 编辑页把边声明在阶段表单上，仓库归一化时按 from 落到对应成员的任务，
 * 解析回表单时又从任务还原成阶段级边——两轮必须不缺不重。
 */
class AgentTeamInterfaceEdgeTest {

    private lateinit var fileAccess: FileAccessProvider
    private lateinit var repository: AgentTeamRepository
    private var writtenContent: String? = null

    @Before
    fun setUp() {
        fileAccess = mockk()
        every { fileAccess.isDirectory(any()) } returns false
        every { fileAccess.listFiles(any()) } returns emptyList()
        every { fileAccess.isFile(any()) } returns false
        every { fileAccess.writeFile(any(), any(), any(), any()) } answers {
            writtenContent = secondArg()
        }
        every { fileAccess.readFile(any()) } answers { writtenContent.orEmpty() }
        repository = AgentTeamRepository(fileAccess)
    }

    private fun savedTeam(name: String): AgentTeam =
        AgentTeamParser.parse(fileAccess, "~/.aharou/teams/$name.md")!!

    @Test
    fun save_distributesStageEdgesToMatchingMemberTasks() {
        val form = AgentTeamForm(
            name = "edge-team",
            description = "",
            scope = AgentDefinitionScope.GLOBAL,
            members = listOf(TeamMemberRef("designer"), TeamMemberRef("engineer")),
            stages = listOf(
                AgentTeamStageForm(
                    title = "build",
                    level = 2,
                    members = listOf("designer", "engineer"),
                    prompt = "deliver the feature",
                    interfaces = listOf(
                        TeamInterfaceEdgeForm("designer", "engineer", "component props contract"),
                        TeamInterfaceEdgeForm("engineer", "designer", "")
                    )
                )
            )
        )
        assertNull(repository.save(form, null))

        val tasks = savedTeam("edge-team").stages.single().tasks
        assertEquals(
            listOf(TeamInterface("engineer", "component props contract")),
            tasks.first { it.agent == "designer" }.interfaces
        )
        assertEquals(
            listOf(TeamInterface("designer", "")),
            tasks.first { it.agent == "engineer" }.interfaces
        )
        assertTrue(tasks.all { it.prompt == "deliver the feature" })
    }

    @Test
    fun save_dropsEdgesWhoseSourceLeftTheStage() {
        val form = AgentTeamForm(
            name = "edge-drop",
            description = "",
            scope = AgentDefinitionScope.GLOBAL,
            members = listOf(TeamMemberRef("designer"), TeamMemberRef("engineer")),
            stages = listOf(
                AgentTeamStageForm(
                    title = "build",
                    level = 2,
                    members = listOf("designer"),
                    prompt = "deliver",
                    interfaces = listOf(
                        TeamInterfaceEdgeForm("designer", "engineer", "contract"),
                        TeamInterfaceEdgeForm("engineer", "designer", "")
                    )
                )
            )
        )
        assertNull(repository.save(form, null))

        val stage = savedTeam("edge-drop").stages.single()
        assertEquals(listOf("designer"), stage.tasks.map { it.agent })
        assertEquals(listOf(TeamInterface("engineer", "contract")), stage.tasks.single().interfaces)
    }

    @Test
    fun toForm_restoresStageLevelEdgesFromTasks() {
        val team = AgentTeam(
            name = "round-trip",
            description = "",
            charter = "",
            stages = listOf(
                TeamStage(
                    title = "build",
                    tasks = listOf(
                        TeamTask(agent = "designer", prompt = "deliver", interfaces = listOf(TeamInterface("engineer", "contract"))),
                        TeamTask(agent = "engineer", prompt = "deliver", interfaces = listOf(TeamInterface("designer", "")))
                    )
                )
            )
        )
        val edges = team.toForm(AgentDefinitionScope.GLOBAL).stages.single().interfaces
        assertEquals(
            listOf(
                TeamInterfaceEdgeForm("designer", "engineer", "contract"),
                TeamInterfaceEdgeForm("engineer", "designer", "")
            ),
            edges
        )
    }

    @Test
    fun save_dedupesEdgesFromDuplicateMemberTasks() {
        // 手写脏数据：同一阶段两条 designer 任务各带同一边 → toForm 回填出重复阶段边 →
        // 落盘必须按 from+with 去重，否则重边被保存固化、再打开再去重就迟了
        val team = AgentTeam(
            name = "dup-edges",
            description = "",
            charter = "",
            stages = listOf(
                TeamStage(
                    title = "build",
                    tasks = listOf(
                        TeamTask(agent = "designer", prompt = "a", interfaces = listOf(TeamInterface("engineer", "contract"))),
                        TeamTask(agent = "designer", prompt = "b", interfaces = listOf(TeamInterface("engineer", "contract"))),
                        TeamTask(agent = "engineer", prompt = "c")
                    )
                )
            ),
            members = listOf(TeamMemberRef("designer"), TeamMemberRef("engineer"))
        )
        assertNull(repository.save(team.toForm(AgentDefinitionScope.GLOBAL), null))

        val designerTasks = savedTeam("dup-edges").stages.single().tasks.filter { it.agent == "designer" }
        assertTrue(designerTasks.isNotEmpty())
        designerTasks.forEach { task ->
            assertEquals(listOf(TeamInterface("engineer", "contract")), task.interfaces)
        }
    }

    @Test
    fun saveThenReload_keepsEdgesRoundTripStable() {
        val edges = listOf(
            TeamInterfaceEdgeForm("designer", "engineer", "contract"),
            TeamInterfaceEdgeForm("engineer", "designer", "")
        )
        val form = AgentTeamForm(
            name = "stable",
            description = "",
            scope = AgentDefinitionScope.GLOBAL,
            members = listOf(TeamMemberRef("designer"), TeamMemberRef("engineer")),
            stages = listOf(
                AgentTeamStageForm(
                    title = "build",
                    level = 2,
                    members = listOf("designer", "engineer"),
                    prompt = "deliver",
                    interfaces = edges
                )
            )
        )
        assertNull(repository.save(form, null))

        // 模拟再次打开编辑页：读回文件 → toForm，阶段级边应与保存前完全一致
        val reloaded = savedTeam("stable").toForm(AgentDefinitionScope.GLOBAL)
        assertEquals(edges, reloaded.stages.single().interfaces)
    }
}
