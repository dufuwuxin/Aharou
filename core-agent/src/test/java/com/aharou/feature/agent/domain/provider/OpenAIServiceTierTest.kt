package com.aharou.feature.agent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIServiceTierTest {
    @Test
    fun selection_roundTripsAndClearPreservesOtherHeaders() {
        val original = mapOf("X-Client" to "test", "openai-service-tier" to "priority")
        assertEquals("fast", OpenAIServiceTier.value(original))
        val selected = OpenAIServiceTier.select(original, "ultrafast")
        assertEquals(mapOf("X-Client" to "test", "OpenAI-Service-Tier" to "ultrafast"), selected)
        assertTrue(OpenAIServiceTier.requiresResponses(selected))
        val cleared = OpenAIServiceTier.select(selected, null)
        assertEquals(mapOf("X-Client" to "test"), cleared)
        assertNull(OpenAIServiceTier.value(cleared))
        assertFalse(OpenAIServiceTier.requiresResponses(cleared))
    }

    @Test
    fun casingAndLegacyAlias_areCanonicalized() {
        val headers = mapOf("openai-service-tier" to " FAST ", "OPENAI-SERVICE-TIER" to "priority")
        assertEquals(mapOf("OpenAI-Service-Tier" to "fast"), OpenAIServiceTier.normalizeHeaders(headers))
    }

    @Test
    fun unknownValue_isNotUpgradedOrChanged() {
        val headers = mapOf("OpenAI-Service-Tier" to "vendor-custom")
        assertEquals("vendor-custom", OpenAIServiceTier.value(headers))
        assertEquals(headers, OpenAIServiceTier.normalizeHeaders(headers))
        assertFalse(OpenAIServiceTier.requiresResponses(headers))
    }

    @Test(expected = IllegalArgumentException::class)
    fun conflictingCaseInsensitiveHeaders_areRejected() {
        OpenAIServiceTier.value(mapOf("OpenAI-Service-Tier" to "fast", "openai-service-tier" to "ultrafast"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSelection_isRejected() {
        OpenAIServiceTier.select(emptyMap(), "turbo")
    }
}
