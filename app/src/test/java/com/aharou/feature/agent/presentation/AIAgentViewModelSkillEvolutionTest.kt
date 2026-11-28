package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.presentation.component.SkillEvolutionSuggestion
import com.aharou.feature.agent.presentation.component.maySaveSkillEvolutionDraft
import com.aharou.feature.agent.domain.skill.SkillScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIAgentViewModelSkillEvolutionTest {
    @Test
    fun suggestionRequiresOptInSuccessfulToolTurnAndManualTrigger() {
        assertFalse(SkillEvolutionSuggestion.maySuggest(enabled = false, successful = true, toolCalls = 1, autoTriggered = false))
        assertFalse(SkillEvolutionSuggestion.maySuggest(enabled = true, successful = false, toolCalls = 1, autoTriggered = false))
        assertFalse(SkillEvolutionSuggestion.maySuggest(enabled = true, successful = true, toolCalls = 0, autoTriggered = false))
        assertFalse(SkillEvolutionSuggestion.maySuggest(enabled = true, successful = true, toolCalls = 1, autoTriggered = true))
        assertTrue(SkillEvolutionSuggestion.maySuggest(enabled = true, successful = true, toolCalls = 1, autoTriggered = false))
    }

    @Test
    fun savingRequiresOptInCurrentSessionCurrentDraftAndWritableScope() {
        assertFalse(maySaveSkillEvolutionDraft(false, true, true, SkillScope.GLOBAL))
        assertFalse(maySaveSkillEvolutionDraft(true, false, true, SkillScope.PROJECT))
        assertFalse(maySaveSkillEvolutionDraft(true, true, false, SkillScope.GLOBAL))
        assertFalse(maySaveSkillEvolutionDraft(true, true, true, SkillScope.BUILTIN))
        assertFalse(maySaveSkillEvolutionDraft(true, true, true, SkillScope.PROJECT))
        assertFalse(maySaveSkillEvolutionDraft(true, true, true, SkillScope.PROJECT, "workspace-a", "workspace-b"))
        assertTrue(maySaveSkillEvolutionDraft(true, true, true, SkillScope.PROJECT, "workspace-a", "workspace-a"))
        assertTrue(maySaveSkillEvolutionDraft(true, true, true, SkillScope.GLOBAL, "workspace-a", "workspace-b"))
    }

    @Test
    fun replacementDraftGetsANewIdentityButEditedCopyKeepsItsIdentity() {
        val first = SkillEvolutionSuggestion.parse("""{"name":"Reusable","description":"desc","instructions":"body"}""")!!
        val edited = first.copy(name = "Reusable edited")
        val replacement = SkillEvolutionSuggestion.parse("""{"name":"Reusable","description":"desc","instructions":"body"}""")!!

        assertTrue(first.draftId == edited.draftId)
        assertFalse(first.draftId == replacement.draftId)
    }

    @Test
    fun malformedDraftAndEmptyFieldsAreRejected() {
        assertTrue(SkillEvolutionSuggestion.parse("not json") == null)
        assertTrue(SkillEvolutionSuggestion.parse("""{"name":"","description":"desc","instructions":"body"}""") == null)
        assertTrue(SkillEvolutionSuggestion.parse("""{"name":"Reusable","description":"desc","instructions":"body"}""") != null)
    }
}
