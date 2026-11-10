package com.aharou.feature.agent.domain.task

import org.junit.Assert.*
import org.junit.Test

class ProjectPlanDraftTest {
    @Test fun exactObservedNovelEnumerationCreatesFiveDeliverables() {
        val text = "请帮我生成五本小说，分别为：\n1. 《星尘纪事》- 硬科幻小说，公元2847年星际殖民时代，边缘星球发现外星遗迹，引发联邦与殖民地战争。\n2. 《青衫客》- 武侠小说，明末江湖，神秘剑客因失传剑谱卷入纷争，在忠义与爱情间抉择。\n3. 《回声小镇》- 悬疑推理小说，退休刑警回到故乡调查连环死亡案，所有案件指向废弃钟楼。\n4. 《时光当铺》- 都市奇幻小说，午夜开门的神秘当铺可用记忆换取时间，女主人公寻找父亲失踪真相。\n5. 《深海王座》- 海洋冒险小说，22世纪深海探险队在马里亚纳海沟发现失落文明，面临企业贪婪与海怪威胁。\n\n每本小说需要包含：书名、作者笔名、题材类型、完整故事大纲（起承转合）、主要人物设定（至少6位）、世界观背景设定、分章大纲（25-30章）、第一章完整正文（不少于3000字）。"
        assertEquals(352, text.length)
        val project = ProjectPlan(workspace = "w", title = "Observed novels", requirements = text)
        val tasks = ProjectPlanDraft.build(project)
        val enumerated = text.lines().filter { it.matches(Regex("^[1-5]\\. .*")) }
        assertEquals(5, tasks.size)
        assertEquals(enumerated, tasks.map { it.requirement })
        assertEquals(enumerated.map { it.substringAfter(". ").take(120) }, tasks.map { it.title })
        assertEquals(listOf("《星尘纪事》", "《青衫客》", "《回声小镇》", "《时光当铺》", "《深海王座》"),
            tasks.map { it.title.substringBefore("》") + "》" })
        val common = text.lineSequence().first() + "\n" + text.substringAfter("\n\n")
        assertTrue(tasks.all { it.parentId == null && it.background == common && it.projectId == project.id })
        assertEquals(text, project.requirements)

        val singleLine = "生成五本小说，每本小说需要：1) 完整的书名、作者名、题材类型；2) 详细的故事大纲（起承转合）；3) 主要人物设定；4) 世界观背景；5) 分章节的详细情节设计（至少20章）；6) 开篇第一章的完整正文内容。"
        assertEquals(105, singleLine.length)
        val original = project.copy(requirements = singleLine)
        assertTrue(ProjectPlanDraft.build(original).isEmpty())
        assertEquals(0, ProjectPlanDraft.preview(original, singleLine))
        assertEquals(singleLine, original.requirements)
    }
    @Test fun enumeratedDeliverablesKeepSharedContextWithoutExtraTasks() {
        val lead = "请创建五个任务，分别为"
        val shared = "每个任务需要独立验证并保留已有成果。"
        for (items in listOf(
            listOf("整理需求", "实现核心", "完善界面", "验证行为", "交付说明"),
            listOf("创作科幻小说", "创作悬疑小说", "创作历史小说", "创作童话小说", "创作冒险小说")
        )) {
            val lines = items.mapIndexed { index, item -> "${index + 1}. $item" }
            val text = (listOf(lead) + lines + shared).joinToString("\n")
            val project = ProjectPlan(workspace = "w", title = "Plan", requirements = text)
            val tasks = ProjectPlanDraft.build(project)
            assertEquals(5, tasks.size)
            assertEquals(lines, tasks.map { it.requirement })
            assertEquals(items, tasks.map { it.title })
            assertTrue(tasks.all { it.parentId == null && lead in it.background && shared in it.background })
            assertEquals(text, project.requirements)
        }
    }
    @Test fun plainMultilineAndSingleInstructionAreNotGuessedIntoFive() {
        val project = ProjectPlan(workspace = "w", title = "Plan", requirements = "请完成这份工作")
        assertEquals(listOf(project.requirements), ProjectPlanDraft.build(project).map { it.requirement })
        assertEquals(2, ProjectPlanDraft.build(project.copy(requirements = "先实现\n再验证")).size)
    }
    @Test fun multiDeliverableOverviewsWaitForConcreteItemsAcrossCategories() {
        for ((overview, items) in listOf(
            "生成2份报告，每份报告需要来源和结论" to listOf("销售报告", "成本报告"),
            "创作三首歌曲，每首歌曲需要歌词和曲谱" to listOf("春日", "夏夜", "秋雨"),
            "请拆成两个任务" to listOf("实现", "验证")
        )) {
            val project = ProjectPlan(workspace = "w", title = "Plan", requirements = overview)
            assertTrue(ProjectPlanDraft.build(project).isEmpty())
            assertEquals(overview, project.requirements)
            val concrete = overview + "\n" + items.mapIndexed { index, item -> "${index + 1}. $item" }.joinToString("\n")
            val tasks = ProjectPlanDraft.build(project.copy(requirements = concrete))
            assertEquals(items, tasks.map { it.title })
            assertTrue(tasks.all { it.background == overview })
        }
    }
    @Test fun inlineConcreteListsKeepTheOriginalExecutableRequirement() {
        for (text in listOf(
            "生成2份报告，分别为销售报告和成本报告，每份报告需要来源和结论",
            "生成2份报告，每份报告需要来源和结论，分别为销售报告和成本报告",
            "生成2份报告，每份报告需要来源和结论，分别为：1)销售报告；2)成本报告",
            "生成2份报告：1)销售报告2)成本报告，每份报告需要来源和结论",
            "生成两份报告：一）销售报告二）成本报告，每份报告需要来源和结论",
            "创作三首歌曲：一、春日 二、夏夜 三、秋雨，每首歌曲需要歌词和曲谱"
        )) {
            val project = ProjectPlan(workspace = "w", title = "Plan", requirements = text)
            assertEquals(listOf(text), ProjectPlanDraft.build(project).map { it.requirement })
            assertEquals(text, project.requirements)
        }
    }
    @Test fun countsAndPerStepParametersAreStillSingleExecutableInstructions() {
        for (text in listOf("生成一份报告，每份报告需要结论", "计算5个样本平均值",
            "生成一份报告，每一个步骤需要说明，使用3个样本", "生成3份报告，每一个步骤需要说明", "生成3份报告", "创建1个任务")) {
            val project = ProjectPlan(workspace = "w", title = "Plan", requirements = text)
            assertEquals(listOf(text), ProjectPlanDraft.build(project).map { it.requirement })
        }
    }
    @Test fun indentedContinuationBelongsOnlyToItsEnumeratedTask() {
        val text = "共同前导\n1. A\n  仅修改 a.txt\n2. B\n  仅修改 b.txt\n每个任务都要验证"
        val tasks = ProjectPlanDraft.build(ProjectPlan(workspace = "w", title = "Plan", requirements = text))
        assertEquals(listOf("A", "B"), tasks.map { it.title })
        assertEquals("1. A\n  仅修改 a.txt", tasks[0].requirement)
        assertEquals("2. B\n  仅修改 b.txt", tasks[1].requirement)
        assertTrue(tasks.all { it.background == "共同前导\n每个任务都要验证" && it.parentId == null })
        assertFalse((tasks[0].requirement + tasks[0].background).contains("b.txt"))
        assertFalse((tasks[1].requirement + tasks[1].background).contains("a.txt"))
    }
    @Test fun numberedAndIndentedRequirementsRemainComplete() {
        val text = "1. Parent requirement\n  - child requirement\n2. Another requirement"
        val project = ProjectPlan(workspace = "w", title = "Plan", requirements = text)
        val tasks = ProjectPlanDraft.build(project)
        assertEquals(3, tasks.size)
        assertEquals(tasks[0].id, tasks[1].parentId)
        assertNull(tasks[2].parentId)
        assertEquals(text, tasks.joinToString("\n") { it.requirement })
        assertTrue(tasks.all { it.projectId == project.id && it.sessionIds.isEmpty() })
    }
    @Test fun naturalSentencesAndBlankParagraphsProduceEditableDrafts() {
        val project = ProjectPlan(workspace = "w", title = "Plan", requirements = "First sentence. Second sentence!\n\nThird requirement")
        val tasks = ProjectPlanDraft.build(project)
        assertEquals(3, tasks.size)
        assertTrue(tasks.all { it.status == ProjectTaskStatus.DRAFT })
        assertTrue(tasks[0].requirement.endsWith("."))
    }
    @Test fun independentProjectsNeverShareTaskIdentityOrParent() {
        val a = ProjectPlan(workspace = "w", title = "A", requirements = "- one\n  - two")
        val b = a.copy(id = "other")
        val tasks = ProjectPlanDraft.build(a) + ProjectPlanDraft.build(b)
        assertEquals(4, tasks.map { it.id }.toSet().size)
        assertNull(ProjectTaskGraph.validate(tasks))
    }
}
