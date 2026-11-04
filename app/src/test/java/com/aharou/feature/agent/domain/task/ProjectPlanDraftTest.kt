package com.aharou.feature.agent.domain.task

import org.junit.Assert.*
import org.junit.Test

class ProjectPlanDraftTest {
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
