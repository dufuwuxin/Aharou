package com.aharou.feature.agent.domain.provider

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheUsageReportingTest {
    @Test fun responsesMissingFieldAndReportedZeroRemainDifferent() {
        assertEquals(-1, parseResponsesUsage(null).cachedInputTokens)
        assertEquals(-1, parseResponsesUsage(JsonParser.parseString("""{"input_tokens":100,"input_tokens_details":{}}""").asJsonObject).cachedInputTokens)
        assertEquals(0, parseResponsesUsage(JsonParser.parseString("""{"input_tokens":100,"input_tokens_details":{"cached_tokens":0}}""").asJsonObject).cachedInputTokens)
        assertEquals(80, parseResponsesUsage(JsonParser.parseString("""{"input_tokens":100,"input_tokens_details":{"cached_tokens":80}}""").asJsonObject).cachedInputTokens)
    }
    @Test fun interactionsMissingFieldAndReportedZeroRemainDifferent() {
        assertEquals(-1, parseInteractionsUsage(null).cachedInputTokens)
        assertEquals(-1, parseInteractionsUsage(JsonParser.parseString("""{"total_input_tokens":100}""").asJsonObject).cachedInputTokens)
        assertEquals(0, parseInteractionsUsage(JsonParser.parseString("""{"total_input_tokens":100,"total_cached_tokens":0}""").asJsonObject).cachedInputTokens)
    }
    @Test fun responseWithoutUsageDefaultsToUnknownInsteadOfAReportedMiss() {
        assertEquals(-1, AIResponse("done").cachedInputTokens)
    }
}
