package com.aharou.feature.agent.domain.tool.custom

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolSettingsStore 的存取与损坏容错（临时目录，纯 JVM，不起 Robolectric）。
 *
 * 约定：凡是要断言「从磁盘加载」的用例，都先把文件写到盘上、再 new store、最后显式 refresh()——
 * store 构造时那次预热读是异步的，先造对象后写盘会与用例自己写入的内容抢着发布快照。
 */
class ToolSettingsStoreTest {

    private class FakeLocator(
        private val global: File,
        private val project: File? = null
    ) : ToolStorageLocator {
        override fun globalToolsDir(): File = global
        override fun projectToolsDir(): File? = project
    }

    private fun tempDir(name: String): File = Files.createTempDirectory("aharou-tools-$name").toFile()

    private fun definition(name: String, mode: ToolInjectionMode = ToolInjectionMode.EVERY_ROUND) =
        CustomToolDefinition(
            name = name,
            description = " 测试工具 ",
            command = "echo {{text}}",
            parameters = listOf(CustomToolParameter(name = "text", required = true)),
            injectionMode = mode
        )

    @Test
    fun schemaOverride_survivesReloadAndClearsWithoutLosingPolicy() = runBlocking {
        val global = tempDir("schema-override")
        val locator = FakeLocator(global)
        val schema = """{"type":"object","title":"Edited"}"""
        val store = ToolSettingsStore(locator)
        assertTrue(store.saveOverride("Bash", ToolOverride(schemaJson = schema, injectionMode = ToolInjectionMode.KEYWORD, keywords = listOf("run"))))
        val reloaded = ToolSettingsStore(locator)
        reloaded.refresh()
        val override = reloaded.snapshotOverrides().getValue("Bash")
        assertEquals(schema, override.schemaJson)
        assertEquals(ToolInjectionMode.KEYWORD, override.injectionMode)
        assertTrue(reloaded.saveOverride("Bash", override.copy(schemaJson = null)))
        val reset = ToolSettingsStore(locator)
        reset.refresh()
        assertNull(reset.snapshotOverrides()["Bash"]?.schemaJson)
        assertEquals(listOf("run"), reset.snapshotOverrides()["Bash"]?.keywords)
    }

    @Test
    fun saveAndDelete_roundTrip() = runBlocking {
        val global = tempDir("save")
        val store = ToolSettingsStore(FakeLocator(global))

        assertTrue(store.saveCustomTool(definition("greet")))
        assertTrue(File(global, "custom/greet.json").isFile)
        assertEquals(listOf("greet"), store.snapshotCustomTools().map { it.name })
        // 落盘前会 normalized：描述去空白
        assertEquals("测试工具", store.snapshotCustomTool("greet")?.description)

        assertTrue(store.deleteCustomTool("greet"))
        assertFalse(File(global, "custom/greet.json").exists())
        assertTrue(store.snapshotCustomTools().isEmpty())
    }

    @Test
    fun createCustomTool_preservesExistingDefinitionAcrossScopes() = runBlocking {
        val global = tempDir("create-global")
        val project = tempDir("create-project")
        val store = ToolSettingsStore(FakeLocator(global, project))
        assertTrue(store.createCustomTool(definition("first")))
        assertFalse(store.createCustomTool(definition("first").copy(command = "overwritten")))
        assertEquals("echo {{text}}", store.snapshotCustomTool("first")?.command)
        val globalStore = ToolSettingsStore(FakeLocator(global))
        assertTrue(globalStore.createCustomTool(definition("global")))
        assertFalse(store.createCustomTool(definition("global").copy(command = "overwritten")))
        globalStore.refresh()
        assertEquals("echo {{text}}", globalStore.snapshotCustomTool("global")?.command)
        assertFalse(File(project, "custom/global.json").exists())
    }

    @Test
    fun saveCustomTool_rejectsInvalidName() = runBlocking {
        val store = ToolSettingsStore(FakeLocator(tempDir("invalid")))
        assertFalse(store.saveCustomTool(definition("bad name!")))
        assertTrue(store.snapshotCustomTools().isEmpty())
    }

    @Test
    fun saveCustomTool_clampsTimeoutAndNormalizesKeywords() = runBlocking {
        val store = ToolSettingsStore(FakeLocator(tempDir("normalize")))
        store.saveCustomTool(
            definition("deploy", ToolInjectionMode.KEYWORD).copy(
                timeoutSeconds = 9_999,
                keywords = listOf(" 上线 ", "", "上线", "deploy")
            )
        )
        val saved = store.snapshotCustomTool("deploy")
        assertEquals(CustomToolDefinition.MAX_TIMEOUT_SECONDS, saved?.timeoutSeconds)
        assertEquals(listOf("上线", "deploy"), saved?.keywords)
        assertEquals(ToolInjectionMode.KEYWORD, saved?.injectionMode)
    }

    @Test
    fun saveAndClearOverride() = runBlocking {
        val global = tempDir("override")
        val store = ToolSettingsStore(FakeLocator(global))

        assertTrue(store.saveOverride("Bash", ToolOverride(description = "跑命令", injectionMode = ToolInjectionMode.OFF)))
        assertEquals("跑命令", store.snapshotOverrides()["Bash"]?.description)
        assertEquals(ToolInjectionMode.OFF, store.snapshotOverrides()["Bash"]?.injectionMode)
        assertTrue(File(global, "overrides.json").isFile)

        assertTrue(store.clearOverride("Bash"))
        assertNull(store.snapshotOverrides()["Bash"])
    }

    @Test
    fun projectLayerWinsOverGlobal() = runBlocking {
        val global = tempDir("global")
        val project = tempDir("project")
        File(global, "custom").mkdirs()
        File(project, "custom").mkdirs()
        File(global, "custom/greet.json").writeText(
            """{"name":"greet","description":"全局版","command":"echo {{text}}","parameters":[{"name":"text","required":true}]}"""
        )
        File(project, "custom/greet.json").writeText(
            """{"name":"greet","description":"项目版","command":"echo {{text}}","parameters":[{"name":"text","required":true}]}"""
        )

        val store = ToolSettingsStore(FakeLocator(global, project))
        store.refresh()

        assertEquals(1, store.snapshotCustomTools().size)
        assertEquals("项目版", store.snapshotCustomTool("greet")?.description)
    }

    @Test
    fun corruptCustomFile_doesNotBreakOthers() = runBlocking {
        val global = tempDir("corrupt")
        File(global, "custom").mkdirs()
        File(global, "custom/broken.json").writeText("{ 这不是 JSON ")
        File(global, "custom/greeting.json").writeText(
            """{"name":"greeting","description":"好的那条","command":"echo {{text}}","parameters":[{"name":"text","required":true}],"injectionMode":"keyword","keywords":["打招呼"]}"""
        )

        val store = ToolSettingsStore(FakeLocator(global))
        store.refresh()

        // 坏文件被跳过，好文件照常生效
        assertEquals(listOf("greeting"), store.snapshotCustomTools().map { it.name })
        assertEquals(ToolInjectionMode.KEYWORD, store.snapshotCustomTool("greeting")?.injectionMode)
    }

    @Test
    fun corruptOverridesFile_isIgnored() = runBlocking {
        val global = tempDir("corrupt-overrides")
        File(global, "custom").mkdirs()
        File(global, "overrides.json").writeText("not json at all")

        val store = ToolSettingsStore(FakeLocator(global))
        store.refresh()

        assertTrue(store.snapshotOverrides().isEmpty())
        // 坏文件不影响后续写入：写一条能被正常读回
        assertTrue(store.saveOverride("readFile", ToolOverride(injectionMode = ToolInjectionMode.OFF)))
        assertEquals(ToolInjectionMode.OFF, store.snapshotOverrides()["readFile"]?.injectionMode)
    }

    @Test
    fun customToolWithoutProject_UsesGlobalLayer() = runBlocking {
        val global = tempDir("no-project")
        val store = ToolSettingsStore(FakeLocator(global))
        store.saveCustomTool(definition("greet"))
        assertTrue(File(global, "custom/greet.json").isFile)
        assertEquals(listOf("greet"), store.snapshotCustomTools().map { it.name })
    }
}
