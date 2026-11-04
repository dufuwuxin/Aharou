package com.aharou.feature.agent.domain.tool.custom

import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.TOOL_TITLE_PARAM
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 注入三态过滤：每轮 / 关键词（大小写、空关键词、空白）/ 关闭，以及内置工具的描述覆盖。 */
class ToolInjectionGateTest {

    private class FakeTool(
        override val name: String,
        override val description: String = "内置描述",
        private val schemaDescription: String? = null
    ) : AgentTool() {
        override val parameters: Map<String, ToolParameter> = emptyMap()
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Success(JsonPrimitive("ok"))

        override fun toJsonSchema(): Map<String, Any> {
            val map = LinkedHashMap<String, Any>()
            map["type"] = "object"
            schemaDescription?.let { map["description"] = it }
            return map
        }
    }

    private class TempLocator(private val root: File) : ToolStorageLocator {
        override fun globalToolsDir(): File = root
        override fun projectToolsDir(): File? = null
    }

    private lateinit var store: ToolSettingsStore
    private lateinit var gate: ToolInjectionGate

    @Before
    fun setUp() {
        val root = File(System.getProperty("java.io.tmpdir"), "aharou-gate-${System.nanoTime()}")
        store = ToolSettingsStore(TempLocator(root))
        gate = ToolInjectionGate(store)
    }

    private fun customTool(name: String, mode: ToolInjectionMode, keywords: List<String> = emptyList()) {
        runBlocking {
            store.saveCustomTool(
                CustomToolDefinition(
                    name = name,
                    description = "自定义",
                    command = "echo {{x}}",
                    parameters = listOf(CustomToolParameter(name = "x", required = true)),
                    injectionMode = mode,
                    keywords = keywords
                )
            )
        }
    }

    private fun names(tools: List<AgentTool>, userText: String?): List<String> =
        gate.visible(tools, userText).map { it.name }

    @Test
    fun inheritedStandardSchemaEditsReachActualVisibleRequest() {
        val tool = object : AgentTool() {
            override val name = "standard"
            override val description = "standard"
            override val parameters = mapOf("path" to ToolParameter("path", com.aharou.feature.agent.domain.tool.ParameterType.STRING, "Original path"))
            override suspend fun execute(args: Map<String, JsonElement>): ToolResult = ToolResult.Success(JsonPrimitive("ok"))
        }
        val original = tool.toJsonSchema()
        val edited = com.google.gson.JsonParser.parseString(com.google.gson.Gson().toJson(original)).asJsonObject
        edited.getAsJsonObject("properties").getAsJsonObject("path").addProperty("description", "Edited path")
        edited.getAsJsonObject("properties").getAsJsonObject(TOOL_TITLE_PARAM).addProperty("description", "Edited title")
        val snapshot = ToolSettingsStore.Snapshot(overrides = mapOf(tool.name to ToolOverride(schemaJson = edited.toString())))
        val result = gate.visible(listOf(tool), "task", snapshot).single().toJsonSchema()
        val json = com.google.gson.JsonParser.parseString(com.google.gson.Gson().toJson(result)).asJsonObject
        assertEquals("Edited path", json.getAsJsonObject("properties").getAsJsonObject("path").get("description").asString)
        assertEquals("Edited title", json.getAsJsonObject("properties").getAsJsonObject(TOOL_TITLE_PARAM).get("description").asString)
        assertEquals(original["required"], result["required"])
    }

    @Test
    fun schemaOverride_appliesToVisibleRequestAndRejectsStaleStructure() {
        val tool = FakeTool("schema")
        val snapshot = ToolSettingsStore.Snapshot(overrides = mapOf("schema" to ToolOverride(
            schemaJson = """{"type":"object","title":"Edited"}""")))
        assertEquals("Edited", gate.visible(listOf(tool), "x", snapshot).single().toJsonSchema()["title"])
        val invalid = snapshot.copy(overrides = mapOf("schema" to ToolOverride(schemaJson = """{"type":"array"}""")))
        assertEquals(tool.toJsonSchema(), gate.visible(listOf(tool), "x", invalid).single().toJsonSchema())
    }

    @Test
    fun schemaWrapper_preservesStreamExecutionAndEveryPermissionHook() = runBlocking {
        val context = com.aharou.feature.agent.domain.model.AgentContext(null, null, "/project", null)
        val args = mapOf<String, JsonElement>("x" to JsonPrimitive("input"))
        val request = com.aharou.feature.agent.domain.tool.PendingToolPermission(
            id = "id", toolName = "stream", title = "title", summary = "summary", details = "details", argsPreview = "args")
        val stream = kotlinx.coroutines.flow.emptyFlow<com.aharou.feature.agent.domain.tool.ToolStreamEvent>()
        val result = ToolResult.Success(JsonPrimitive("original"))
        val tool = object : AgentTool(), com.aharou.feature.agent.domain.tool.StreamingAgentTool {
            override val name = "stream"
            override val description = "original"
            override val parameters = emptyMap<String, ToolParameter>()
            override val permissionPolicy = com.aharou.feature.agent.domain.tool.ToolPermissionPolicy.ASK
            override val capabilities = setOf(com.aharou.feature.agent.domain.tool.ToolCapability.EXECUTE_COMMANDS)
            override val acceptsSecrets = true
            override val deferredLoading = true
            override fun effectivePermissionPolicy(mode: com.aharou.feature.agent.domain.model.AgentMode) =
                com.aharou.feature.agent.domain.tool.ToolPermissionPolicy.AUTO_APPROVE
            override fun effectiveCapabilities(args: Map<String, JsonElement>) = emptySet<com.aharou.feature.agent.domain.tool.ToolCapability>()
            override fun permissionCommand(args: Map<String, JsonElement>) = "original command"
            override fun isCallSensitive(args: Map<String, JsonElement>) = true
            override fun buildPermissionRequest(callId: String, args: Map<String, JsonElement>, argsPreview: String) = request
            override suspend fun buildPermissionPreview(callId: String, args: Map<String, JsonElement>, context: com.aharou.feature.agent.domain.model.AgentContext) = request
            override suspend fun execute(args: Map<String, JsonElement>) = result
            override suspend fun executeWithContext(args: Map<String, JsonElement>, context: com.aharou.feature.agent.domain.model.AgentContext) = result
            override fun executeStream(args: Map<String, JsonElement>, context: com.aharou.feature.agent.domain.model.AgentContext) = stream
        }
        val edited = com.google.gson.Gson().toJson(tool.toJsonSchema()).dropLast(1) + ",\"title\":\"Edited\"}"
        val visible = gate.visible(listOf(tool), "x", ToolSettingsStore.Snapshot(overrides = mapOf("stream" to ToolOverride(schemaJson = edited)))).single()
        assertTrue(visible is com.aharou.feature.agent.domain.tool.StreamingAgentTool)
        assertEquals("Edited", visible.toJsonSchema()["title"])
        assertEquals(tool.parameters, visible.parameters)
        assertEquals(tool.permissionPolicy, visible.permissionPolicy)
        assertEquals(tool.capabilities, visible.capabilities)
        assertEquals(tool.acceptsSecrets, visible.acceptsSecrets)
        assertEquals(tool.deferredLoading, visible.deferredLoading)
        com.aharou.feature.agent.domain.model.AgentMode.entries.forEach { mode ->
            assertEquals(tool.effectivePermissionPolicy(mode), visible.effectivePermissionPolicy(mode))
        }
        assertEquals(tool.effectiveCapabilities(args), visible.effectiveCapabilities(args))
        assertEquals(tool.permissionCommand(args), visible.permissionCommand(args))
        assertEquals(tool.isCallSensitive(args), visible.isCallSensitive(args))
        assertTrue(visible.buildPermissionRequest("id", args, "args") === request)
        assertTrue(visible.buildPermissionPreview("id", args, context) === request)
        assertTrue(visible.execute(args) === result)
        assertTrue(visible.executeWithContext(args, context) === result)
        assertTrue((visible as com.aharou.feature.agent.domain.tool.StreamingAgentTool).executeStream(args, context) === stream)
    }

    @Test
    fun everyRound_isAlwaysInjected() {
        customTool("deploy", ToolInjectionMode.EVERY_ROUND)
        assertEquals(listOf("deploy"), names(listOf(FakeTool("deploy")), "随便聊聊"))
    }

    @Test
    fun off_isDropped() {
        customTool("deploy", ToolInjectionMode.OFF)
        assertTrue(names(listOf(FakeTool("deploy")), "deploy 一下").isEmpty())
    }

    @Test
    fun keyword_matchesCaseInsensitively() {
        customTool("deploy", ToolInjectionMode.KEYWORD, listOf("Deploy"))
        assertEquals(listOf("deploy"), names(listOf(FakeTool("deploy")), "please DEPLOY now"))
    }

    @Test
    fun keyword_ignoresWhitespace() {
        customTool("deploy", ToolInjectionMode.KEYWORD, listOf("git  log"))
        assertEquals(listOf("deploy"), names(listOf(FakeTool("deploy")), "帮我跑一下 gitlog"))
    }

    @Test
    fun keyword_matchesChinese() {
        customTool("deploy", ToolInjectionMode.KEYWORD, listOf("编译"))
        assertEquals(listOf("deploy"), names(listOf(FakeTool("deploy")), "先编译再发包"))
        assertTrue(names(listOf(FakeTool("deploy")), "先跑个测试").isEmpty())
    }

    @Test
    fun keyword_withEmptyListFallsBackToEveryRound() {
        customTool("deploy", ToolInjectionMode.KEYWORD, emptyList())
        assertEquals(listOf("deploy"), names(listOf(FakeTool("deploy")), "无关的一句话"))
    }

    @Test
    fun keyword_withNullUserTextStillInjected() {
        customTool("deploy", ToolInjectionMode.KEYWORD, listOf("部署"))
        assertEquals(listOf("deploy"), names(listOf(FakeTool("deploy")), null))
    }

    @Test
    fun override_appliesThreeStatesToBuiltinTool() {
        runBlocking {
            store.saveOverride("readFile", ToolOverride(injectionMode = ToolInjectionMode.OFF))
            store.saveOverride("Bash", ToolOverride(injectionMode = ToolInjectionMode.KEYWORD, keywords = listOf("跑命令")))
        }
        val tools = listOf(FakeTool("readFile"), FakeTool("Bash"), FakeTool("list"))
        assertEquals(listOf("list"), names(tools, "看看文件"))
        assertEquals(listOf("Bash", "list"), names(tools, "帮我跑命令看看"))
    }

    @Test
    fun override_replacesDescription() {
        runBlocking { store.saveOverride("readFile", ToolOverride(description = "读文件（团队约定版）")) }
        val tool = FakeTool("readFile", description = "原始描述")
        val visible = gate.visible(listOf(tool), "x")
        assertEquals("读文件（团队约定版）", visible.single().description)
    }

    @Test
    fun override_replacesSchemaDescriptionWhenPresent() {
        runBlocking { store.saveOverride("readFile", ToolOverride(description = "新描述")) }
        val tool = FakeTool("readFile", description = "原始描述", schemaDescription = "原始描述")
        val schema = gate.visible(listOf(tool), "x").single().toJsonSchema()
        assertEquals("新描述", schema["description"])
    }

    @Test
    fun noSettings_keepsEverything() {
        assertEquals(
            listOf("readFile", "Bash"),
            names(listOf(FakeTool("readFile"), FakeTool("Bash")), null)
        )
    }

    @Test
    fun matchesAny_isWhitespaceAndCaseInsensitive() {
        assertTrue(gate.matchesAny(listOf("Git Log"), "看下 GIT  log"))
        assertFalse(gate.matchesAny(listOf("git log"), "看下 git"))
    }
}
