package com.aharou.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication
import com.aharou.feature.settings.presentation.component.SkillsLibraryContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

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

    @Test fun selectedTagsUseUnionAndKeepOneRowPerScopedSkillInTagOrder() {
        val multi = entry("multi", tags = listOf("daily", "finance"))
        val project = entry("multi", SkillScope.PROJECT, listOf("daily"))
        val untyped = entry("old")
        val source = listOf(untyped, multi, project, multi)
        assertEquals(listOf(multi, project, untyped), filterSkillLibrary(source, null, emptyList()))
        assertEquals(listOf(multi, project), filterSkillLibrary(source, null, listOf("tag:daily", "tag:finance")))
        assertEquals(listOf(multi), filterSkillLibrary(source, SkillScope.GLOBAL, listOf("tag:daily")))
        assertEquals(listOf(untyped), filterSkillLibrary(source, null, listOf("untagged")))
        assertTrue(filterSkillLibrary(source, null, listOf("tag:missing")).isEmpty())
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


@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectTaskConversationTestApplication::class, sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SkillTagsLibraryComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val multi = SkillUiEntry("multi", "daily report", SkillScope.GLOBAL, false, "body",
        tags = listOf("daily", "finance"), typeIds = listOf("report"), typeNames = listOf("Reports"))
    private val project = multi.copy(name = "project", scope = SkillScope.PROJECT, tags = listOf("daily"))
    private val builtin = multi.copy(name = "builtin", scope = SkillScope.BUILTIN, builtin = true)
    private val deleted = mutableListOf<SkillUiEntry>()
    private val opened = mutableListOf<SkillUiEntry>()
    private val exported = mutableListOf<SkillUiEntry>()
    private val moved = mutableListOf<Triple<String, SkillScope, SkillScope>>()
    private var managementClicks = 0

    @Test fun manyTagsStayInOneTopRowAndLeaveMostHeightForSkills() {
        open(listOf(multi.copy(tags = (1..30).map { "tag-%02d".format(it) })))
        val bar = compose.onNodeWithTag("skills_tag_bar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("skills_search").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("skills_list").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(bar.bottom <= search.top)
        assertTrue(bar.height <= with(compose.density) { 56.dp.toPx() })
        assertTrue(list.height >= with(compose.density) { 360.dp.toPx() })
        val firstRow = row(multi).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(firstRow.height <= with(compose.density) { 120.dp.toPx() })
        compose.onNodeWithTag("skills_tag_bar").performScrollToNode(hasTestTag("skills_tag:tag:tag-30"))
        compose.onNodeWithTag("skills_tag:tag:tag-30").assertIsDisplayed().performClick().assertIsSelected()
        row(multi).assertIsDisplayed()
        compose.onAllNodesWithText(compose.activity.getString(R.string.skills_type_manage)).assertCountEquals(1)
        compose.onNodeWithText(compose.activity.getString(R.string.skills_type_manage)).performClick()
        compose.runOnIdle { assertEquals(1, managementClicks) }
    }

    @Test fun tagClicksIntersectSearchAndSourceWithoutDuplicatingRows() {
        open()
        selectTag("finance")
        row(project).assertDoesNotExist()
        selectTag("daily")
        compose.onNodeWithTag("skills_tag_bar").performScrollToNode(hasTestTag("skills_tag:tag:finance"))
        compose.onNodeWithTag("skills_tag:tag:finance").assertIsSelected()
        row(project).assertIsDisplayed()
        compose.onAllNodesWithText("multi", substring = false).assertCountEquals(1)
        compose.onNodeWithTag("skills_scope_bar").performScrollToNode(hasTestTag("skills_scope:GLOBAL"))
        compose.onNodeWithTag("skills_scope:GLOBAL").performClick()
        row(project).assertDoesNotExist()
        row(builtin).assertDoesNotExist()
        compose.onNodeWithTag("skills_search").performTextInput("type:report tag:finance missing")
        compose.onNodeWithText(compose.activity.getString(R.string.skills_search_empty)).assertIsDisplayed()
        compose.onNodeWithTag("skills_search").performTextReplacement("type:report tag:finance")
        row(multi).assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(multi), opened) }
        compose.onNodeWithTag("skills_tag_bar").performScrollToNode(hasTestTag("skills_tags_all"))
        compose.onNodeWithTag("skills_tags_all").performClick().assertIsSelected()
        row(multi).assertIsDisplayed()
    }

    @Test fun selectionStaysAboveListAndBatchDeleteCountsScopedSkillsOnlyOnce() {
        open()
        compose.onNodeWithTag("skills_select").performClick()
        compose.onNodeWithTag("skills_selection_bar").assertIsDisplayed()
        row(multi).performTouchInput { click() }
        compose.onNodeWithText(compose.activity.getString(R.string.provider_models_selected_count, 1)).assertIsDisplayed()
        row(builtin).performTouchInput { click() }
        compose.onNodeWithText(compose.activity.getString(R.string.provider_models_selected_count, 1)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        compose.onNodeWithText(compose.activity.getString(R.string.provider_models_select_all)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_models_selected_count, 2)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_delete)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.common_batch_delete_confirm, 2)).assertIsDisplayed()
        compose.onNode(hasText(compose.activity.getString(R.string.common_delete)) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(setOf(multi, project), deleted.toSet()) }
        compose.onNodeWithTag("skills_selection_bar").assertDoesNotExist()
    }

    @Test fun selectedTagSourceAndQuerySurviveSavedStateRestore() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { content(listOf(multi, project, builtin)) }
        selectTag("daily")
        compose.onNodeWithTag("skills_scope_bar").performScrollToNode(hasTestTag("skills_scope:GLOBAL"))
        compose.onNodeWithTag("skills_scope:GLOBAL").performClick()
        compose.onNodeWithTag("skills_search").performTextInput("type:report")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("skills_tag:tag:daily").assertIsSelected()
        compose.onNodeWithTag("skills_scope:GLOBAL").assertIsSelected()
        compose.onNodeWithTag("skills_search").assertTextContains("type:report")
        row(multi).assertIsDisplayed()
        row(project).assertDoesNotExist()
    }

    @Test fun longPressStillExportsAndMovesTheExactScopedEntry() {
        open(listOf(multi))
        row(multi).performTouchInput { longClick() }
        compose.onNodeWithText(compose.activity.getString(R.string.skills_export)).performClick()
        compose.runOnIdle { assertEquals(listOf(multi), exported) }
        row(multi).performTouchInput { longClick() }
        compose.onNodeWithText(compose.activity.getString(R.string.skills_move_to_project)).performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("multi", SkillScope.GLOBAL, SkillScope.PROJECT)), moved) }
    }

    private fun open(entries: List<SkillUiEntry> = listOf(multi, project, builtin)) {
        compose.setContent { content(entries) }
    }

    @androidx.compose.runtime.Composable
    private fun content(entries: List<SkillUiEntry>) {
        MaterialTheme {
            Box(Modifier.requiredSize(360.dp, 640.dp)) {
                SkillsLibraryContent(entries = entries, onDelete = { deleted += it },
                    onDeleteBatch = { deleted += it }, onMove = { name, from, to -> moved += Triple(name, from, to) },
                    onExport = { exported += it }, onOpenDetail = { opened += it },
                    typeManager = {
                        TextButton(onClick = { managementClicks++ }) {
                            Text(androidx.compose.ui.res.stringResource(R.string.skills_type_manage))
                        }
                    })
            }
        }
    }

    private fun selectTag(label: String) {
        val tag = "skills_tag:tag:$label"
        compose.onNodeWithTag("skills_tag_bar").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick().assertIsSelected()
    }

    private fun row(entry: SkillUiEntry) = compose.onNodeWithTag("skill_row:${skillSelectionId(entry)}")
}
