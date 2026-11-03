package com.aharou.feature.agent.domain.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFragmentInjectionTest {
    @Test fun legacyDefaultsPreserveMainRulesWithoutLeakingToBaseMembers() {
        val policy = PromptFragmentInjection()
        assertTrue(policy.matches(""))
        assertFalse(policy.matches("", "reviewer"))
        assertTrue(policy.matches("", "reviewer", inheritsMainRules = true))
    }

    @Test fun defaultOffActuallyRemovesMainAndInheritedBaseline() {
        val policy = PromptFragmentInjection(defaultInjection = false)
        assertFalse(policy.matches(""))
        assertFalse(policy.matches("", "reviewer", inheritsMainRules = true))
    }

    @Test fun namedMemberDoesNotRequireMainRulesOrExposeToOthers() {
        val policy = PromptFragmentInjection(defaultInjection = false, members = setOf("reviewer"))
        assertTrue(policy.matches("", "reviewer"))
        assertFalse(policy.matches("", "writer"))
        assertFalse(policy.matches(""))
    }

    @Test fun keywordsUseCurrentTextIgnoreCaseAndIgnoreBlanks() {
        val policy = PromptFragmentInjection(defaultInjection = false, keywords = listOf(" ", " Kotlin "))
        assertTrue(policy.matches("fix KOTLIN"))
        assertTrue(policy.matches("fix kotlin", "reviewer"))
        assertFalse(policy.matches("unrelated"))
        assertFalse(policy.matches(""))
    }

    @Test fun multipleMatchingRoutesStillProduceOneBooleanDecision() {
        val policy = PromptFragmentInjection(keywords = listOf("fix"), members = setOf("reviewer"))
        assertTrue(policy.matches("fix", "reviewer", inheritsMainRules = true))
    }
    @Test fun disabledKeywordAndMemberSwitchesRetainListsWithoutInjecting() {
        val policy = PromptFragmentInjection(
            defaultInjection = false, keywords = listOf("Kotlin"), members = setOf("reviewer"),
            keywordsEnabled = false, membersEnabled = false
        )
        assertFalse(policy.matches("Kotlin"))
        assertFalse(policy.matches("Kotlin", "reviewer"))
        assertTrue(policy.copy(keywordsEnabled = true).matches("Kotlin"))
        assertTrue(policy.copy(membersEnabled = true).matches("unrelated", "reviewer"))
    }

}
