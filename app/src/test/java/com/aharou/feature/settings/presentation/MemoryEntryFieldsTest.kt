package com.aharou.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.feature.agent.domain.context.*
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication
import com.aharou.feature.settings.presentation.component.ContextLibraryEditor
import com.aharou.feature.settings.presentation.component.memoryRulesFromDraft
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectTaskConversationTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MemoryEntryFieldsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val selected = mutableStateOf(entry("first"))
    private val saves = mutableListOf<WorldbookRules>()
    private var dismissed = false

    private fun entry(id: String) = ContextEntry(id, id, "summary", "body", MemoryScope.GLOBAL,
        ContextKind.REFERENCE, ContextLoad.FULL, ContextOrigin.MEMORY, id,
        worldbook = WorldbookRules(keywordWeight = 2.0, tagWeight = 3.0,
            relations = listOf(WorldbookRelation("target")), tags = listOf(id)))

    private fun open(restoration: StateRestorationTester? = null, expandConditions: Boolean = true) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            MaterialTheme {
                ContextLibraryEditor(selected.value, ContextCategory.LONG_TERM, "", ContextLibraryState(),
                    onBack = { dismissed = true },
                    onSave = { _, _, _, _, _, _, _, _, _, rules -> saves += rules }, onDelete = null)
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        if (expandConditions) text(R.string.context_injection_conditions).performScrollTo().performClick()
    }

    private fun field(label: Int) = compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(label)))
    private fun text(label: Int) = compose.onNodeWithText(compose.activity.getString(label))
    private fun replace(label: Int, value: String) { field(label).performScrollTo().performTextReplacement(value) }
    private fun save() { text(R.string.common_save).performScrollTo().assertIsEnabled().performClick() }

    @Test fun conditionsStartCollapsedAndFieldsAppearAfterExpanding() {
        open(expandConditions = false)
        val triggers = field(R.string.context_worldbook_triggers)
        triggers.assertDoesNotExist()
        text(R.string.context_injection_conditions).performScrollTo().performClick()
        field(R.string.context_worldbook_triggers).assertExists()
    }

    @Test fun inlineEditsCommitTogetherOnceAndPreserveAdvancedMetadata() {
        open()
        replace(R.string.asset_classification_tags, "mobile, android")
        replace(R.string.context_worldbook_triggers, "Kotlin\nAndroid\n")
        replace(R.string.context_worldbook_excludes, "ignore")
        replace(R.string.context_worldbook_confidence, "0.75")
        replace(R.string.context_worldbook_importance, "80")
        assertTrue(saves.isEmpty())
        text(R.string.context_worldbook_apply).assertDoesNotExist()
        text(R.string.context_worldbook_title).assertDoesNotExist()
        save()
        val rules = saves.single()
        assertEquals(listOf("mobile", "android"), rules.tags)
        assertEquals(listOf("Kotlin", "Android"), rules.triggers)
        assertEquals(listOf("ignore"), rules.excludes)
        assertEquals(0.75, rules.confidence!!, 0.0)
        assertEquals(80, rules.importance)
        assertEquals(selected.value.worldbook.relations, rules.relations)
        assertEquals(2.0, rules.keywordWeight, 0.0)
        assertEquals(3.0, rules.tagWeight, 0.0)
    }

    @Test fun cancelDiscardsAllInlineEditsWithoutCallingSave() {
        open()
        replace(R.string.context_worldbook_confidence, "0.5")
        replace(R.string.context_worldbook_triggers, "new condition")
        text(R.string.context_back_library).performScrollTo().performClick()
        text(R.string.context_discard).performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertTrue(saves.isEmpty())
            assertEquals(emptyList<String>(), selected.value.worldbook.triggers)
            assertNull(selected.value.worldbook.confidence)
        }
    }

    @Test fun invalidNumbersBlockParentSaveUntilCorrectedOrCleared() {
        open()
        for (invalid in listOf("NaN", "Infinity", "1.1", "-0.1", "abc")) {
            replace(R.string.context_worldbook_confidence, invalid)
            text(R.string.common_save).performScrollTo().assertIsNotEnabled()
            field(R.string.context_worldbook_confidence).assertTextContains(invalid)
        }
        replace(R.string.context_worldbook_confidence, "")
        replace(R.string.context_worldbook_importance, "101")
        text(R.string.common_save).performScrollTo().assertIsNotEnabled()
        replace(R.string.context_worldbook_importance, "40")
        text(R.string.context_worldbook_more).performScrollTo().performClick()
        replace(R.string.context_worldbook_priority, "2147483648")
        text(R.string.common_save).performScrollTo().assertIsNotEnabled()
        replace(R.string.context_worldbook_priority, "-2")
        save()
        assertNull(saves.single().confidence)
        assertEquals(40, saves.single().importance)
        assertEquals(-2, saves.single().priority)
    }

    @Test fun entrySwitchDoesNotReusePreviousEntriesInlineDraft() {
        open()
        replace(R.string.context_worldbook_triggers, "first draft")
        replace(R.string.context_worldbook_confidence, "bad")
        compose.runOnIdle { selected.value = entry("second").copy(worldbook = WorldbookRules(triggers = listOf("second"))) }
        text(R.string.context_injection_conditions).performScrollTo().performClick()
        field(R.string.context_worldbook_triggers).performScrollTo().assertTextContains("second")
        save()
        assertEquals(listOf("second"), saves.single().triggers)
        assertNull(saves.single().confidence)
    }

    @Test fun savedStateRestorationRetainsRawInvalidAndMultilineDraftThenSaves() {
        val restoration = StateRestorationTester(compose)
        open(restoration)
        replace(R.string.context_worldbook_triggers, "one\ntwo\n")
        replace(R.string.context_worldbook_confidence, "unfinished")
        restoration.emulateSavedInstanceStateRestore()
        field(R.string.context_worldbook_triggers).performScrollTo().assertTextContains("one\ntwo\n")
        field(R.string.context_worldbook_confidence).performScrollTo().assertTextContains("unfinished")
        text(R.string.common_save).performScrollTo().assertIsNotEnabled()
        replace(R.string.context_worldbook_confidence, "0.4")
        save()
        assertEquals(listOf("one", "two"), saves.single().triggers)
    }

    @Test fun unknownAndMalformedJsonRemainRecoverableWithoutImplicitOverwrite() {
        open()
        text(R.string.context_worldbook_show_json).performScrollTo().performClick()
        val raw = "{\"unknown\":{\"keep\":42},\"confidence\":0.2}"
        replace(R.string.context_worldbook_field, raw)
        text(R.string.common_save).performScrollTo().assertIsNotEnabled()
        replace(R.string.context_worldbook_triggers, "condition")
        field(R.string.context_worldbook_field).assertTextContains("\"keep\":42", substring = true)
        replace(R.string.context_worldbook_field, "{broken")
        text(R.string.common_save).performScrollTo().assertIsNotEnabled()
        field(R.string.context_worldbook_field).assertTextContains("{broken")
        replace(R.string.context_worldbook_field, "{\"confidence\":0.2}")
        save()
        assertEquals(0.2, saves.single().confidence!!, 0.0)
    }

    @Test fun normalizationIsDraftOnlyAndNeverConflatesKeywordsWithTriggers() {
        val raw = "{\"triggers\":\"one\\ntwo\\n\",\"tags\":\"android, mobile\",\"confidence\":\"0.4\"}"
        val draft = ContextEditorDraft.from(null, ContextCategory.RULE, "", false).copy(worldbookJson = raw)
        assertEquals(raw, ContextEditorDraft.restore(draft.savedFields()).worldbookJson)
        assertEquals(listOf("one", "two"), memoryRulesFromDraft(raw)!!.triggers)
        assertNull(memoryRulesFromDraft("{\"unknown\":1}"))
        assertNull(memoryRulesFromDraft("{\"priority\":\"\"}"))
    }
}
