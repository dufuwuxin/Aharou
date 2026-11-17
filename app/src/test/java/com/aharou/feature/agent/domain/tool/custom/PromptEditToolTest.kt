package com.aharou.feature.agent.domain.tool.custom

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.plugin.data.PluginRecordStore
import dagger.Lazy
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PromptEditToolTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun actualCommandUsesVersionsAndRoundVisibilityWithoutChangingExecution() = runBlocking {
        val locator = object : ToolStorageLocator {
            override fun globalToolsDir() = File(temp.root, "global")
            override fun projectToolsDir(): File? = File(temp.root, "B")
            override fun projectToolsDir(projectRoot: String): File? = File(temp.root, projectRoot)
        }
        val store = ToolSettingsStore(locator)
        val registry = ToolRegistry()
        val context = AgentContext(null, null, "A", null)
        val allowed = fake("visible")
        registry.register(allowed.name, allowed)
        registry.register("hidden", fake("hidden"))
        registry.bindDiscoveryTools(listOf(allowed), context)
        val tool = PromptEditTool(store, Lazy { registry }, mockk<PluginRecordStore>())
        fun args(action: String, id: String = "visible", content: String? = null, version: String? = null) = buildMap<String, JsonElement> {
            put("action", JsonPrimitive(action)); put("target", JsonPrimitive("tool")); put("id", JsonPrimitive(id))
            content?.let { put("content", JsonPrimitive(it)) }; version?.let { put("version", JsonPrimitive(it)) }
        }
        val listed = tool.executeWithContext(args("list"), context) as ToolResult.Success
        assertEquals("[\"visible\"]", listed.data.toString())
        assertEquals("NOT_FOUND", (tool.executeWithContext(args("get", "hidden"), context) as ToolResult.Error).code)
        val read = (tool.executeWithContext(args("get"), context) as ToolResult.Success).data.jsonObject
        val version = read.getValue("version").jsonPrimitive.content
        val updated = tool.executeWithContext(args("set", content = "new instructions", version = version), context) as ToolResult.Success
        assertEquals("new instructions", updated.data.jsonObject.getValue("content").jsonPrimitive.content)
        assertFalse(File(temp.root, "B").exists())
        assertEquals("PROMPT_VERSION_CONFLICT", (tool.executeWithContext(args("set", content = "stale", version = version), context) as ToolResult.Error).code)
        val nextVersion = updated.data.jsonObject.getValue("version").jsonPrimitive.content
        val reset = tool.executeWithContext(args("reset", version = nextVersion), context) as ToolResult.Success
        assertEquals("original", reset.data.jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("original", registry.getTool("visible")!!.description)
        assertEquals(allowed.toJsonSchema(), registry.getTool("visible")!!.toJsonSchema())
    }

    private fun fake(id: String): AgentTool = object : AgentTool() {
        override val name = id
        override val description = "original"
        override val parameters = emptyMap<String, ToolParameter>()
        override suspend fun execute(args: Map<String, JsonElement>) = ToolResult.Success(JsonPrimitive("unchanged"))
    }
}
