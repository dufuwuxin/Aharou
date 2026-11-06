package com.aharou.feature.agent.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptVariableExpanderTest {
    private val blockVariables = listOf(
        PromptVars.SKILLS_VAR,
        PromptVars.MEMORY_VAR,
        PromptVars.SUBAGENTS_VAR,
        PromptVars.PROJECT_RULES_VAR,
        PromptVars.RULES_VAR
    )
    private val allVariables = blockVariables + listOf(PromptVars.WORKSPACE_VAR, PromptVars.DATE_VAR)

    @Test
    fun expandsAllKnownVariablesInOriginalOrder() {
        assertEquals(
            "skills|memories|agents|project|rules|workspace|date",
            expand(allVariables.joinToString("|"), "skills", "memories", "agents", "project", "rules")
        )
    }

    @Test
    fun expandsEachBlockOnlyAtItsFirstOccurrence() {
        val raw = (blockVariables + blockVariables + blockVariables).joinToString("|")
        assertEquals(
            "skills|memories|agents|project|rules||||||||||",
            expand(raw, "skills", "memories", "agents", "project", "rules")
        )
    }

    @Test
    fun removesAllOccurrencesOfNullBlocksIncludingRules() {
        val raw = blockVariables.joinToString("") { "a${it}b$it" }
        assertEquals("ab".repeat(blockVariables.size), expand(raw))
    }

    @Test
    fun removesAllOccurrencesOfEmptyBlocks() {
        val raw = blockVariables.joinToString("|") { "$it$it" }
        assertEquals("||||", expand(raw, "", "", "", "", ""))
    }

    @Test
    fun preservesUnknownAndMalformedPlaceholders() {
        val raw = "{{INSTRUCTION}} {{AICODE_UNKNOWN}} {{aicode_skills}} {{AICODE_SKILLS}"
        assertEquals(raw, expand(raw, skills = "unused"))
    }

    @Test
    fun leavesUnrelatedTextUnchanged() {
        assertEquals("", expand(""))
        assertEquals("plain text\n中文", expand("plain text\n中文"))
    }

    @Test
    fun neverParsesKnownMarkersInsideAnyReplacement() {
        // Every source carries both earlier and later variables, including its own marker.
        val payload = allVariables.joinToString(";") + " {{INSTRUCTION}} $1 \\path"
        val raw = allVariables.joinToString("|")
        assertEquals(
            List(allVariables.size) { payload }.joinToString("|"),
            expand(raw, payload, payload, payload, payload, payload, payload, payload)
        )
    }

    @Test
    fun scalarOccurrencesAreNotDeduplicated() {
        val raw = "${PromptVars.WORKSPACE_VAR}:${PromptVars.DATE_VAR}:" +
            "${PromptVars.WORKSPACE_VAR}:${PromptVars.DATE_VAR}"
        assertEquals("workspace:date:workspace:date", expand(raw))
    }

    @Test
    fun preservesIdenticalContentFromDifferentSourcesAndScopes() {
        val raw = blockVariables.joinToString("|")
        val memories = "global: same content\nproject: same content"
        assertEquals(
            "same content|$memories|same content|same content|same content",
            expand(raw, "same content", memories, "same content", "same content", "same content")
        )
    }

    @Test
    fun doesNotDeduplicateIdenticalOriginalAndInsertedText() {
        assertEquals("same|same|", expand("same|${PromptVars.SKILLS_VAR}|${PromptVars.SKILLS_VAR}", skills = "same"))
    }

    @Test
    fun rawPlaceholdersPreventTailFromRepeatingExpandedBlocks() {
        val raw = (blockVariables + blockVariables).joinToString("|")
        val tail = PromptTail(skills = "skills", memories = "memories", subAgents = "agents", projectRules = "project", rules = "rules")
        val rendered = expand(raw, "skills", "memories", "agents", "project", "rules")
        assertEquals("skills|memories|agents|project|rules|||||", rendered + PromptTailAssembler.render(raw, tail))
    }

    @Test
    fun absentPlaceholdersStillAppendIdenticalSourcesInTail() {
        val raw = "prefix"
        val tail = PromptTail(skills = "same", memories = "same", subAgents = "same", projectRules = "same", rules = "same")
        assertEquals("prefix" + "\n\nsame".repeat(5), expand(raw) + PromptTailAssembler.render(raw, tail))
    }

    @Test
    fun insertedMarkerDoesNotSuppressAnotherSourcesTail() {
        val raw = PromptVars.SKILLS_VAR
        val skills = "literal ${PromptVars.MEMORY_VAR}"
        val tail = PromptTail(skills = skills, memories = "memory tail")
        assertEquals("$skills\n\nmemory tail", expand(raw, skills = skills, memories = "memory tail") + PromptTailAssembler.render(raw, tail))
    }

    private fun expand(
        text: String,
        skills: String? = null,
        memories: String? = null,
        subAgents: String? = null,
        projectRules: String? = null,
        rules: String? = null,
        workspace: String = "workspace",
        date: String = "date"
    ): String = PromptVariableExpander.expand(text, skills, memories, subAgents, projectRules, workspace, date, rules)
}
