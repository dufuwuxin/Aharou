package com.aharou.core.privacy

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.privacy.maskedOutboundRequest
import com.aharou.feature.agent.domain.privacy.restoredFromOutbound
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.domain.workflow.ContextTokenEstimator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class OutboundRequestMaskingTest {
    @Test fun discoveriesInLaterMessagesAlsoCoverEarlierSystemAndMessageOccurrences() {
        val secret = "AbCdEf987654321"
        val messages = listOf(AgentMessage.UserMessage(content = "Use $secret"),
            AgentMessage.ToolResultMessage(toolName = "readFile", result = "password=$secret"))
        val masked = maskedOutboundRequest("Remember $secret", messages, OutboundMasker.create())
        assertFalse(masked.systemPrompt.contains(secret))
        assertFalse((masked.messages[0] as AgentMessage.UserMessage).content.contains(secret))
        assertFalse((masked.messages[1] as AgentMessage.ToolResultMessage).result.contains(secret))
        assertEquals("Use $secret", (messages[0] as AgentMessage.UserMessage).content)
    }

    @Test fun offModeDoesNotCopyOrRewriteTheNativeRequest() {
        val messages = listOf(AgentMessage.UserMessage(content = "password=AbCdEf987654321"))
        val request = maskedOutboundRequest("original system", messages, null)
        assertSame(messages, request.messages)
        assertEquals("original system", request.systemPrompt)
    }

    @Test fun completeToolArgumentsDoNotConsumeAStreamingTextTail() {
        val masker = OutboundMasker.create()
        val token = masker.mask("password=AbCdEf987654321").substringAfter('=')
        assertEquals("", masker.restore(token.take(5)))
        val argument = JsonObject(mapOf("items" to JsonArray(listOf(JsonPrimitive(token)))))
        val final = AIResponse("literal {{", listOf(ToolCall("call", "echo", mapOf("nested" to argument))))
            .restoredFromOutbound(masker)
        assertEquals("literal {{", final.content)
        assertEquals("AbCdEf987654321", ((final.toolCalls[0].arguments["nested"] as JsonObject)["items"] as JsonArray)[0].let { (it as JsonPrimitive).content })
        assertEquals("AbCdEf987654321", masker.restore(token.drop(5)))
        assertEquals("", masker.flush())
    }

    @Test fun signedReasoningMetadataAndImagePayloadsRemainUntouched() {
        val signed = AgentMessage.AssistantMessage(content = "password=AbCdEf987654321",
            reasoning = "opaque signed reasoning", signature = "signature-exact", thinkingBlocksJson = "[{\"data\":\"opaque\"}]")
        val masked = maskedOutboundRequest("system", listOf(signed), OutboundMasker.create()).messages.single() as AgentMessage.AssistantMessage
        assertEquals(signed.reasoning, masked.reasoning)
        assertEquals(signed.signature, masked.signature)
        assertEquals(signed.thinkingBlocksJson, masked.thinkingBlocksJson)
        assertFalse(masked.content.contains("AbCdEf987654321"))
    }

    @Test fun largeSecretFixtureReducesTheExistingEstimatorButNoSecretTextStaysIdentical() {
        val pem = "-----BEGIN PRIVATE KEY-----\n" + "ABCDEF123456".repeat(200) + "\n-----END PRIVATE KEY-----"
        val messages = listOf(AgentMessage.UserMessage(content = pem))
        val masker = OutboundMasker.create()
        val request = maskedOutboundRequest("system", messages, masker)
        val before = ContextTokenEstimator.estimate("system", messages, emptyList())
        val after = ContextTokenEstimator.estimate(request.systemPrompt, request.messages, emptyList())
        assertTrue("Only this fixture's local estimate is compared", after < before)
        assertEquals(pem, masker.restoreComplete((request.messages.single() as AgentMessage.UserMessage).content))
        val ordinary = "Explain how a binary search works"
        assertEquals(ordinary, OutboundMasker.create().mask(ordinary))
    }
}
