package com.aharou.feature.agent.domain.tool.custom

import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
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
