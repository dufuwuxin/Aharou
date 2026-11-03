package com.aharou.feature.agent.domain.tool.custom

import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolProjectScopeTest {
    @Test fun backgroundSessionUsesItsProjectAndKeepsDefinitionAndOverrideAfterRefresh() = runBlocking {
        val root = Files.createTempDirectory("tool-scope").toFile()
        try {
            fun write(project: String, command: String, description: String) {
                val dir = File(root, "$project/.aharou/tools")
                File(dir, "custom").mkdirs()
                File(dir, "custom/same.json").writeText(
                    """{"name":"same","description":"$project","command":"$command"}""")
                File(dir, "overrides.json").writeText("""{"same":{"description":"$description"}}""")
            }
            write("a", "printf A", "A override")
            write("b", "printf B", "B override")
            val locator = object : ToolStorageLocator {
                override fun globalToolsDir() = File(root, "global")
                override fun projectToolsDir() = File(root, "b/.aharou/tools") // UI is B
            }
            val store = ToolSettingsStore(locator)
            store.refresh()
            val gate = ToolInjectionGate(store)
            val captured = gate.snapshotForProject(File(root, "a").path)
            val source = object : CustomToolSource {
                override fun customTools(): List<AgentTool> = error("Runtime must use captured snapshot")
                override fun customTools(snapshot: ToolSettingsStore.Snapshot): List<AgentTool> = snapshot.tools.map { definition ->
                    object : AgentTool() {
                        override val name = definition.name
                        override val description = definition.description
                        override val parameters = emptyMap<String, ToolParameter>()
                        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = ToolResult.Success(JsonPrimitive(definition.command))
                    }
                }
            }
            val tools = gate.visible(ToolRegistry(customToolSource = source).getAvailableTools(captured), "", captured)
            write("a", "printf CHANGED", "changed override")
            store.refresh()
            gate.snapshotForProject(File(root, "b").path)
            assertEquals("A override", tools.single().description)
            assertEquals(ToolResult.Success(JsonPrimitive("printf A")), tools.single().execute(emptyMap()))
            assertEquals("printf A", captured.tools.single().command)
            assertEquals("printf B", store.snapshotCustomTools().single().command)
        } finally { root.deleteRecursively() }
    }

    @Test fun blankSessionRootDoesNotBorrowCurrentUiProject() = runBlocking {
        val root = Files.createTempDirectory("tool-no-project").toFile()
        try {
            val project = File(root, "b/.aharou/tools/custom").apply { mkdirs() }
            File(project, "same.json").writeText("""{"name":"same","command":"printf B"}""")
            val store = ToolSettingsStore(object : ToolStorageLocator {
                override fun globalToolsDir() = File(root, "global")
                override fun projectToolsDir() = project.parentFile
            })
            assertEquals(emptyList<CustomToolDefinition>(), store.snapshotForProject("").tools)
        } finally { root.deleteRecursively() }
    }
}
