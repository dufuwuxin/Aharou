package com.aharou.core.translation

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AIStreamChunk
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.StreamingAgentTool
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.ToolStreamEvent
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
    private fun toolSchema(prefix: String = ""): Map<String, Any> = linkedMapOf(
        "type" to "object", "description" to "${prefix}根说明", "\$id" to "urn:tool:raw",
        "required" to listOf("description", "输入"),
        "properties" to linkedMapOf(
            "description" to mapOf("type" to "string", "description" to "${prefix}字段说明"),
            "输入" to mapOf(
                "type" to "array", "description" to "${prefix}参数说明", "enum" to listOf("保留选项"),
                "default" to mapOf("description" to "实例描述"),
                "examples" to listOf(mapOf("description" to "实例例子")),
                "items" to mapOf("anyOf" to listOf(
                    mapOf("description" to "${prefix}嵌套说明", "\$ref" to "#/\$defs/保留类型"),
                    false,
                )),
            ),
        ),
        "\$defs" to Json.parseToJsonElement("""{"保留类型":{"type":"string","description":"${prefix}定义说明","const":{"description":"原始常量"}}}"""),
    )

    private class RecordingTool(val schema: Map<String, Any>) : AgentTool(), StreamingAgentTool {
        override val name = "mcp__raw_tool"
        override val description = "工具说明"
        override val parameters = mapOf("输入" to ToolParameter("输入", ParameterType.ARRAY, "参数说明", false,
            listOf("保留选项"), mapOf("description" to "元素说明", "type" to "string")))
        override val permissionPolicy = ToolPermissionPolicy.ASK
        override val capabilities = setOf(ToolCapability.EXTERNAL_TOOL)
        override val acceptsSecrets = true
        override val deferredLoading = true
        val result = ToolResult.Success(JsonPrimitive("raw result"))
        val events = flowOf<ToolStreamEvent>(ToolStreamEvent.Completed(result))
        val permission = PendingToolPermission("id", name, "title", "summary", "details", "preview")
        var receivedArgs: Map<String, JsonElement>? = null
        var receivedContext: AgentContext? = null
        override fun toJsonSchema() = schema
        override fun effectivePermissionPolicy(mode: AgentMode) = ToolPermissionPolicy.AUTO_APPROVE
        override fun effectiveCapabilities(args: Map<String, JsonElement>) = setOf(ToolCapability.NETWORK_WRITE)
        override fun isCallSensitive(args: Map<String, JsonElement>) = true
        override fun permissionCommand(args: Map<String, JsonElement>) = "raw command"
        override fun buildPermissionRequest(callId: String, args: Map<String, JsonElement>, argsPreview: String) = permission
        override suspend fun buildPermissionPreview(callId: String, args: Map<String, JsonElement>, context: AgentContext) = permission
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult { receivedArgs = args; return result }
        override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
            receivedArgs = args; receivedContext = context; return result
        }
        override fun executeStream(args: Map<String, JsonElement>, context: AgentContext) = events
    }

    @Test fun completeProjectsOnlyDescriptionsAndDelegatesAllToolBehavior() = runBlocking {
        val fake = RecordingProvider()
        val calls = mutableListOf<Pair<String, String>>()
        val originalSchema = toolSchema()
        val tool = RecordingTool(originalSchema)
        val routed = LanguageRoutingProvider(fake.provider, { ProviderLanguagePolicy.ENGLISH }, ProviderTextTranslator { text, target ->
            calls += text to target; "en:$text"
        })
        assertSame(fake.response, routed.complete("系统", emptyList(), listOf(tool)))
        @Suppress("UNCHECKED_CAST") val sent = (fake.arguments[2] as List<AgentTool>).single()
        assertNotSame(tool, sent)
        assertEquals("en:工具说明", sent.description)
        assertEquals(toolSchema("en:"), sent.toJsonSchema())
        assertEquals(tool.parameters.getValue("输入").copy(description = "en:参数说明",
            itemsSchema = mapOf("description" to "en:元素说明", "type" to "string")), sent.parameters["输入"])
        for (text in listOf("工具说明", "参数说明", "根说明", "字段说明", "嵌套说明", "定义说明", "元素说明")) {
            assertTrue("Missing translation: $text", text to "en" in calls)
        }
        assertFalse(calls.any { it.first in listOf("保留选项", "实例描述", "实例例子", "原始常量", "输入", "urn:tool:raw") })
        assertSame(originalSchema, tool.toJsonSchema())
        assertEquals(toolSchema(), tool.toJsonSchema())
        assertEquals("工具说明", tool.description)
        assertEquals(tool.name, sent.name)
        assertEquals(tool.permissionPolicy, sent.permissionPolicy)
        assertSame(tool.capabilities, sent.capabilities)
        assertEquals(tool.acceptsSecrets, sent.acceptsSecrets)
        assertEquals(tool.deferredLoading, sent.deferredLoading)
        val args = mapOf<String, JsonElement>("输入" to JsonPrimitive("raw payload"))
        val context = AgentContext(null, null, "/project", null)
        assertSame(tool.result, sent.execute(args))
        assertSame(args, tool.receivedArgs)
        assertSame(tool.result, sent.executeWithContext(args, context))
        assertSame(args, tool.receivedArgs)
        assertSame(context, tool.receivedContext)
        assertEquals(tool.effectivePermissionPolicy(context.mode), sent.effectivePermissionPolicy(context.mode))
        assertEquals(tool.effectiveCapabilities(args), sent.effectiveCapabilities(args))
        assertTrue(sent.isCallSensitive(args))
        assertEquals("raw command", sent.permissionCommand(args))
        assertSame(tool.permission, sent.buildPermissionRequest("call", args, "preview"))
        assertSame(tool.permission, sent.buildPermissionPreview("call", args, context))
        assertTrue(sent is StreamingAgentTool)
        assertSame(tool.events, (sent as StreamingAgentTool).executeStream(args, context))
    }

    @Test fun streamProjectsToolsInSelectedLanguageAndKeepsRawEvents() = runBlocking {
        val fake = RecordingProvider()
        val calls = mutableListOf<Pair<String, String>>()
        val tool = RecordingTool(toolSchema())
        val routed = LanguageRoutingProvider(fake.provider, { ProviderLanguagePolicy.CHINESE }, ProviderTextTranslator { text, target ->
            calls += text to target; "zh:$text"
        })
        val events = routed.completeStream("System", emptyList(), listOf(tool)).toList()
        @Suppress("UNCHECKED_CAST") val sent = (fake.arguments[2] as List<AgentTool>).single()
        assertEquals("zh:工具说明", sent.description)
        assertEquals(toolSchema("zh:"), sent.toJsonSchema())
        assertTrue(calls.all { it.second == "zh" })
        events.zip(fake.events).forEach { (actual, expected) -> assertSame(expected, actual) }
    }

    @Test fun builtInSchemaIncludesTranslatedTitleWithoutChangingStructuredDescriptions() = runBlocking {
        val fake = RecordingProvider()
        val calls = mutableListOf<String>()
        val structured = "{\"message\":\"保持原样\"}"
        val tool = object : AgentTool() {
            override val name = "raw_builtin"
            override val description = "内置说明"
            override val parameters = mapOf("payload" to ToolParameter("payload", ParameterType.STRING, structured))
        }
        val routed = LanguageRoutingProvider(fake.provider, { ProviderLanguagePolicy.ENGLISH }, ProviderTextTranslator { text, _ ->
            calls += text; "en:$text"
        })
        routed.complete("System", emptyList(), listOf(tool))
        @Suppress("UNCHECKED_CAST") val sent = (fake.arguments[2] as List<AgentTool>).single()
        val original = tool.toJsonSchema()
        @Suppress("UNCHECKED_CAST") val originalProperties = original["properties"] as Map<String, Map<String, Any>>
        @Suppress("UNCHECKED_CAST") val sentProperties = sent.toJsonSchema()["properties"] as Map<String, Map<String, Any>>
        val titleDescription = originalProperties.getValue("tool_title").getValue("description") as String
        assertEquals("en:$titleDescription", sentProperties.getValue("tool_title")["description"])
        assertTrue(titleDescription in calls)
        assertFalse(structured in calls)
        assertEquals(structured, sent.parameters.getValue("payload").description)
        assertEquals(originalProperties["payload"], sentProperties["payload"])
        assertEquals(original["required"], sent.toJsonSchema()["required"])
        assertEquals("en:内置说明", sent.description)
        assertFalse(sent is StreamingAgentTool)
    }

    @Test fun nativeKeepsExecutableToolListInBothRequestPaths() = runBlocking {
        val fake = RecordingProvider()
        val tools = listOf(RecordingTool(toolSchema()))
        val routed = LanguageRoutingProvider(fake.provider, { ProviderLanguagePolicy.NATIVE }, ProviderTextTranslator { _, _ -> error("must not translate") })
        routed.complete("System", emptyList(), tools)
        assertSame(tools, fake.arguments[2])
        routed.completeStream("System", emptyList(), tools).toList()
        assertSame(tools, fake.arguments[2])
    }

    @Test(expected = CancellationException::class) fun toolProjectionCancellationDoesNotSendRequest() = runBlocking {
        val fake = RecordingProvider()
        val tool = RecordingTool(toolSchema())
        val routed = LanguageRoutingProvider(fake.provider, { ProviderLanguagePolicy.ENGLISH }, ProviderTextTranslator { text, _ ->
            if (text == "工具说明") throw CancellationException("cancel tool projection") else text
        })
        try { routed.complete("System", emptyList(), listOf(tool)) }
        finally { assertTrue(fake.arguments.isEmpty()) }
        Unit
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
