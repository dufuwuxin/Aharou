package com.aharou.core.translation

import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AIStreamChunk
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LanguageRoutingProviderTest {
    private class RecordingProvider {
        var arguments: Array<out Any?> = emptyArray()
        val response = AIResponse("Original reply", reasoning = "Original reasoning", signature = "opaque", thinkingBlocksJson = "[]")
        val events = listOf(AIStreamChunk.TextDelta("Hello"), AIStreamChunk.ReasoningDelta("raw"),
            AIStreamChunk.ToolCallDeclared("Bash"), AIStreamChunk.Final(response))
        val provider = Proxy.newProxyInstance(AIProvider::class.java.classLoader, arrayOf(AIProvider::class.java)) { _, method, args ->
            when (method.name) {
                "complete" -> { arguments = args!!; response }
                "completeStream" -> { arguments = args!!; flowOf(*events.toTypedArray()) }
                else -> null
            }
        } as AIProvider
    }
    @Test fun completeRoutesSystemAndOrderedBodiesButKeepsTransportFields() = runBlocking {
        val fake = RecordingProvider()
        val calls = mutableListOf<Pair<String, String>>()
        val routed = LanguageRoutingProvider(fake.provider, { ProviderLanguagePolicy.ENGLISH }, ProviderTextTranslator { text, target ->
            calls += text to target; "translated:$text"
        })
        val image = AgentImage("image/png", "image-data", "/tmp/image.png")
        val user = AgentMessage.UserMessage(content = "你好世界", images = listOf(image))
        val assistant = AgentMessage.AssistantMessage(content = "先前回答", reasoning = "思考", signature = "opaque", thinkingBlocksJson = "[]")
        val tool = AgentMessage.ToolResultMessage(toolName = "Bash", result = "\"Hello world\"", modelResult = "{\"ok\":true}")
        val source = listOf(user, assistant, tool)
        val result = routed.complete("系统说明", source, emptyList(), "high")
        assertSame(fake.response, result)
        assertEquals(listOf("系统说明" to "en", "你好世界" to "en", "先前回答" to "en"), calls)
        assertEquals("translated:系统说明", fake.arguments[0])
        @Suppress("UNCHECKED_CAST") val sent = fake.arguments[1] as List<AgentMessage>
        assertEquals(user.copy(content = "translated:你好世界"), sent[0])
        assertSame(user.images, (sent[0] as AgentMessage.UserMessage).images)
        assertEquals(assistant.copy(content = "translated:先前回答"), sent[1])
        assertSame(tool, sent[2])
        assertEquals("high", fake.arguments[3])
        assertEquals("先前回答", assistant.content)
    }
    @Test fun nativeBypassesTranslationAndRetainsListIdentity() = runBlocking {
        val fake = RecordingProvider()
        val source = listOf(AgentMessage.UserMessage(content = "Hello"))
        val routed = LanguageRoutingProvider(fake.provider, { ProviderLanguagePolicy.NATIVE }, ProviderTextTranslator { _, _ -> error("must not translate") })
        routed.complete("System", source)
        assertSame(source, fake.arguments[1])
    }
    @Test fun streamRoutesChineseAndPassesEveryRawEventUnchanged() = runBlocking {
        val fake = RecordingProvider()
        var selected = ProviderLanguagePolicy.CHINESE
        val calls = mutableListOf<String>()
        val routed = LanguageRoutingProvider(fake.provider, { selected }, ProviderTextTranslator { text, target -> calls += target; "中文:$text" })
        val source = listOf(AgentMessage.UserMessage(content = "Hello world"))
        val events = routed.completeStream("System prompt", source).toList()
        assertEquals(listOf("zh", "zh"), calls)
        assertEquals("中文:System prompt", fake.arguments[0])
        events.zip(fake.events).forEach { (actual, expected) -> assertSame(expected, actual) }
        selected = ProviderLanguagePolicy.NATIVE
        routed.completeStream("System prompt", source).toList()
        assertSame(source, fake.arguments[1])
        assertEquals(2, calls.size)
    }
    @Test fun structuredUserBodySkipsTranslator() = runBlocking {
        val fake = RecordingProvider()
        val source = listOf(AgentMessage.UserMessage(content = "{\"message\":\"Hello\"}"))
        val routed = LanguageRoutingProvider(fake.provider, { ProviderLanguagePolicy.ENGLISH }, ProviderTextTranslator { text, _ -> text })
        routed.complete("", source)
        assertEquals(source, fake.arguments[1])
    }
    @Test(expected = CancellationException::class) fun cancellationNeverBecomesSuccessfulCompletion() = runBlocking {
        val fake = RecordingProvider()
        LanguageRoutingProvider(fake.provider, { ProviderLanguagePolicy.ENGLISH }, ProviderTextTranslator { _, _ -> throw CancellationException("cancel") })
            .complete("Translate this", emptyList())
        Unit
    }
}
