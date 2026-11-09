package com.aharou.feature.agent.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptVariableExpanderTest {
    @Test fun valuesAreOnlyResolvedForExplicitMarkers() {
        var calls = 0
        assertEquals("plain text", PromptVariableExpander.expandNative("plain text") { calls++; "hidden" })
        assertEquals(0, calls)
    }

    @Test fun repeatedMarkersUseOneSnapshot() {
        var calls = 0
        val result = PromptVariableExpander.expandNative("{{AICODE_TEAMS}} / {{AICODE_TEAMS}}") { calls++; "team" }
        assertEquals("team / team", result)
        assertEquals(1, calls)
    }

    @Test fun dynamicMetadataIsNeverExpandedAgain() {
        val result = PromptVariableExpander.expandNative("{{AICODE_TEAMS}}") { "description {{AICODE_DATE}}" }
        assertEquals("description {{AICODE_DATE}}", result)
    }

    @Test fun plainTextAndUnknownMarkersDoNotTriggerImplicitContent() {
        assertEquals("a  b", PromptVariableExpander.expandNative("a {{AICODE_UNKNOWN}} b") { "" })
    }

    @Test fun bothLiteralBracesAreRequiredAndInvalidMarkersRemainUntouched() {
        val text = "{AICODE_DATE} {{AICODE_DATE} {{AICODE_date}} {{OTHER_DATE}}"
        var calls = 0
        assertEquals(text, PromptVariableExpander.expandNative(text) { calls++; "unexpected" })
        assertEquals(0, calls)
    }

    @Test fun adjacentMarkersPreserveLiteralReplacementCharacters() {
        val value = "\$1 \\ path {value}"
        assertEquals(value + value, PromptVariableExpander.expandNative("{{AICODE_DATE}}{{AICODE_TEAMS}}") { value })
    }
}
