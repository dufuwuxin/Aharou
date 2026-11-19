package com.aharou.feature.settings.presentation

import com.aharou.feature.agent.domain.skill.SkillScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillTagsLibraryTest {
    private fun entry(name: String, scope: SkillScope = SkillScope.GLOBAL, tags: List<String> = emptyList()) =
        SkillUiEntry(name, "description", scope, false, "body", tags = tags)

    @Test fun selectionTogglesIndependentlyAndKeepsChosenOrder() {
        val both = toggleSkillType(toggleSkillType(emptyList(), "report"), "code")
        assertEquals(listOf("report", "code"), both)
        assertEquals(listOf("code"), toggleSkillType(both, "report"))
    }

    @Test fun groupsUseAllTagsAndTypesAndSelectionIdentityIsUniqueAcrossGroups() {
        val multi = entry("multi", tags = listOf("finance", "daily", "finance")).copy(
            typeId = "report", typeIds = listOf("report", "code"), typeNames = listOf("Reports", "Code"))
        val otherScope = entry("multi", SkillScope.PROJECT, listOf("daily"))
        val untyped = entry("old")
        val groups = groupSkillsByTag(listOf(multi, otherScope, untyped, multi))
        assertEquals(listOf("Code", "daily", "finance", "Reports", null), groups.map { it.label })
        assertEquals(2, groups.single { it.label == "daily" }.entries.size)
        val rows = groups.flatMap { it.entries }
        assertEquals(4, rows.count { skillSelectionId(it) == skillSelectionId(multi) })
        assertEquals(3, rows.map(::skillSelectionId).distinct().size)
        assertEquals(listOf(untyped), groups.last().entries)
    }

    @Test fun secondTypeSearchScopeFilterAndDisabledOrderingRemainFunctional() {
        val enabled = entry("z", tags = listOf("shared")).copy(typeIds = listOf("first", "second"), typeNames = listOf("First", "Second"))
        val disabled = entry("a", tags = listOf("shared")).copy(disabled = true)
        val project = entry("p", SkillScope.PROJECT, listOf("shared"))
        assertTrue(enabled.matchesSkillQuery("type:second tag:shared"))
        assertTrue(enabled.matchesSkillQuery("Second"))
        assertFalse(enabled.matchesSkillQuery("type:sec"))
        val scoped = listOf(disabled, project, enabled).filter { it.scope == SkillScope.GLOBAL && it.matchesSkillQuery("tag:shared") }
        assertEquals(listOf(enabled, disabled), groupSkillsByTag(scoped).single { it.label == "shared" }.entries)
    }
}
