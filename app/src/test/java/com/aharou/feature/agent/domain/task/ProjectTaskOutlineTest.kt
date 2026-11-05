package com.aharou.feature.agent.domain.task

import com.aharou.feature.agent.domain.task.ProjectTaskOutline.Hit
import com.aharou.feature.agent.domain.task.ProjectTaskOutline.Kind
import com.aharou.feature.agent.domain.task.ProjectTaskOutline.Line
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectTaskOutlineTest {
    private fun task(id: String, title: String = id, requirement: String = "") =
        ProjectTask(id = id, workspace = "w", projectId = "p", title = title, requirement = requirement, order = 1, createdAt = 1)

    @Test fun sentencesDropMarkersFencesAndTableRulesButKeepDecimals() {
        assertEquals(listOf("登录页加验证码。", "注册页不变。", "v1.2 版本保留", "3.5 倍间距", "改 A 文件"),
            ProjectTaskOutline.sentences("- 登录页加验证码。注册页不变。\n\n```kotlin\n1. v1.2 版本保留\n|---|---|\n3.5 倍间距\n（2）改 A 文件\n```"))
    }

    @Test fun linesFollowKindOrderAndSkipBlanksAndMissingDependencies() {
        val up = task("up", title = "调研接口")
        val t = task("t", requirement = "实现登录").copy(
            supplements = listOf(" 加上验证码 ", " "), constraints = listOf("不改数据库"), acceptance = "能登录",
            background = "旧版有问题", owner = " builder ", members = listOf("reviewer", " ", "reviewer"),
            dependencyIds = listOf("up", "gone"), brief = "已完成")
        assertEquals(listOf(
            Line(Kind.REQUIREMENT, "实现登录"), Line(Kind.SUPPLEMENT, "加上验证码"), Line(Kind.CONSTRAINT, "不改数据库"),
            Line(Kind.ACCEPTANCE, "能登录"), Line(Kind.BACKGROUND, "旧版有问题"), Line(Kind.OWNER, "builder"),
            Line(Kind.MEMBER, "reviewer"), Line(Kind.MEMBER, "reviewer"), Line(Kind.DEPENDENCY, "调研接口", "up"),
            Line(Kind.RESULT, "已完成")
        ), ProjectTaskOutline.of(t, listOf(up, t)))
    }

    @Test fun findMatchesTitlesAndLinesIgnoringCaseAndIgnoresBlankQueries() {
        val tasks = listOf(task("a", title = "Login 页面", requirement = "改样式"), task("b", title = "接口", requirement = "调用 login 接口"))
        assertEquals(emptyList<Hit>(), ProjectTaskOutline.find(tasks, "  "))
        assertEquals(listOf(Hit("a", null), Hit("b", Line(Kind.REQUIREMENT, "调用 login 接口"))), ProjectTaskOutline.find(tasks, "LOGIN"))
    }

    @Test fun findDoesNotMatchDownstreamTasksThroughTheirDependencyLines() {
        val tasks = listOf(task("a", title = "登录接口"), task("b", title = "登录页").copy(dependencyIds = listOf("a")))
        assertEquals(listOf(Hit("a", null)), ProjectTaskOutline.find(tasks, "登录接口"))
    }
}
