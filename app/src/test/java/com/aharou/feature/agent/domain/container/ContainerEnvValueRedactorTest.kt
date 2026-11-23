package com.aharou.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerEnvValueRedactorTest {
    @Test fun replacesInjectedValuesAndJsonEscapedValues() {
        val result = ContainerEnvValueRedactor.redact(
            "KEY=private-token\njson=private\\nvalue",
            mapOf("API_KEY" to "private-token", "MULTILINE" to "private\nvalue")
        )
        assertTrue(result.hit)
        assertEquals("KEY=[已隐藏]\njson=[已隐藏]", result.text)
    }

    @Test fun masksLongLinesFromMultilineValuesWhenOutputIsStreamedByLine() {
        val result = ContainerEnvValueRedactor.redact("first line secret", mapOf("TOKEN" to "first line secret\nsecond line"))
        assertEquals("[已隐藏]", result.text)
    }

    @Test fun longerValuesAreReplacedBeforeTheirSubstrings() {
        val result = ContainerEnvValueRedactor.redact(
            "prefix abcdef suffix",
            mapOf("SHORT" to "abcde", "LONG" to "abcdef")
        )
        assertEquals("prefix [已隐藏] suffix", result.text)
    }

    @Test fun masksShortInjectedValuesButIgnoresEmptyValues() {
        val result = ContainerEnvValueRedactor.redact("port=80 yes", mapOf("PORT" to "80", "EMPTY" to " "))
        assertTrue(result.hit)
        assertEquals("port=[已隐藏] yes", result.text)
    }
}
