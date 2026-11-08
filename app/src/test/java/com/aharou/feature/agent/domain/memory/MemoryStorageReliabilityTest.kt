package com.aharou.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 覆盖记忆存储层的数据正确性保证：元数据不丢、写盘失败必须上报、归档失败不得删源条目。
 * 全部走内存数据源 + 临时目录，不依赖 Android 框架。
 */
class MemoryStorageReliabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TempDirSource(private val dir: File) : MemorySource {
        override val root: File get() = dir

        override fun listMemories(): List<Memory> =
            dir.listFiles { file -> file.isFile && file.extension == "md" }
                ?.mapNotNull { MemoryParser.parse(it, MemoryScope.GLOBAL) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()

        override fun loadContent(name: String): String? =
            listMemories().firstOrNull { it.name.equals(name, ignoreCase = true) }?.content

        override fun saveMemory(
            name: String, description: String, content: String, type: MemoryType?,
            expiresAt: Long?, pending: Boolean?, keywords: List<String>?
        ): Boolean = MemoryFileStorage.save(
            dir, MemoryScope.GLOBAL, name, description, content, type, expiresAt, pending, keywords
        )

        override fun archiveContent(
            name: String, description: String, content: String, type: MemoryType?,
            keywords: List<String>, expiresAt: Long?, pending: Boolean
        ): Boolean = MemorySource.writeArchiveFile(
            dir, name, description, content, "test", type, keywords, expiresAt, pending
        ) != null

        override fun deleteMemory(name: String): Boolean =
            MemorySource.resolveMemoryFile(dir, name).delete()
    }

    @Test
    fun editMemory_keepsTypeKeywordsExpiresAtAndPending() {
        val source = TempDirSource(tempFolder.root)
        val expiresAt = System.currentTimeMillis() + 86_400_000L
        assertTrue(
            source.saveMemory("demo", "摘要", "old body", MemoryType.IDENTITY, expiresAt, true, listOf("k1", "k2"))
        )

        val result = source.editMemory("demo", listOf(MemoryEdit("old body", "new body", false)))
        assertTrue(result is MemoryEditResult.Success)

        val memory = source.listMemories().first { it.name == "demo" }
        assertEquals("new body", memory.content)
        assertEquals(MemoryType.IDENTITY, memory.type)
        assertEquals(listOf("k1", "k2"), memory.keywords)
        assertEquals(expiresAt, memory.expiresAt)
        assertTrue(memory.pending)
    }

    @Test
    fun archiveContent_thenRestore_keepsTypeKeywordsExpiresAtAndPending() {
        val source = TempDirSource(tempFolder.root)
        val expiresAt = System.currentTimeMillis() + 86_400_000L
        assertTrue(
            source.archiveContent("archived", "摘要", "body", MemoryType.WORKFLOW, listOf("a"), expiresAt, true)
        )

        val archive = source.listArchive().single()
        assertTrue(source.restoreArchive(archive.fileName))

        val memory = source.listMemories().first { it.name == "archived" }
        assertEquals("body", memory.content)
        assertEquals(MemoryType.WORKFLOW, memory.type)
        assertEquals(listOf("a"), memory.keywords)
        assertEquals(expiresAt, memory.expiresAt)
        assertTrue(memory.pending)
    }

    @Test
    fun saveMemory_reportsFalse_whenDiskWriteFails() {
        val blocker = tempFolder.newFile("blocker")
        val source = TempDirSource(File(blocker, "subdir"))
        assertFalse(source.saveMemory("x", "d", "c", null, null, null, null))
    }

    @Test
    fun merge_abortsAndKeepsSources_whenArchivingFails() {
        val dir = tempFolder.root
        val source = TempDirSource(dir)
        assertTrue(source.saveMemory("a1", "d1", "body1", MemoryType.FACT, null, null, listOf("t")))
        assertTrue(source.saveMemory("a2", "d2", "body2", MemoryType.FACT, null, null, listOf("t")))
        val group = source.listMemories().filter { it.name == "a1" || it.name == "a2" }.sortedBy { it.name }

        // 让归档目录无法创建：同名占位成普通文件，归档必然失败。
        File(dir, MemorySource.ARCHIVE_DIR).writeText("block")

        assertFalse(MemoryFileStorage.merge(group, "merged", "合并摘要", "合并正文"))
        assertTrue(File(dir, "a1.md").exists())
        assertTrue(File(dir, "a2.md").exists())
        assertFalse(File(dir, "merged.md").exists())
    }
    @Test fun renameAndEditPreserveUnicodeAndAllMetadata() {
        val file = File(tempFolder.root, "原名.md")
        val expiry = System.currentTimeMillis() + 86_400_000L
        file.writeText(MemoryParser.format("原名", "原说明", "原正文", MemoryType.WORKFLOW,
            listOf("中文", "key"), expiry, true,
            com.aharou.feature.agent.domain.context.ContextKind.INSTRUCTION,
            com.aharou.feature.agent.domain.context.ContextLoad.FULL,
            com.aharou.feature.agent.domain.context.ContextCategory.TEMPORARY, "父/子", "s1"))
        val before = MemoryParser.parse(file, MemoryScope.PROJECT)!!
        assertTrue(MemoryFileStorage.renameChecked(before,
            before.copy(name = "新 名称", description = "新说明🌱", content = "改名后的正文"), before.version))
        val target = MemorySource.resolveMemoryFile(tempFolder.root, "新 名称")
        val after = MemoryParser.parse(target, MemoryScope.PROJECT)!!
        assertFalse(file.exists())
        assertEquals("新 名称", after.name)
        assertEquals("新说明🌱", after.description)
        assertEquals("改名后的正文", after.content)
        assertEquals(before.type, after.type)
        assertEquals(before.keywords, after.keywords)
        assertEquals(before.expiresAt, after.expiresAt)
        assertEquals(before.pending, after.pending)
        assertEquals(before.contextKind, after.contextKind)
        assertEquals(before.contextLoad, after.contextLoad)
        assertEquals(before.contextCategory, after.contextCategory)
        assertEquals(before.contextFolder, after.contextFolder)
        assertEquals(before.contextSessionId, after.contextSessionId)
    }

    @Test fun renameCollisionAndStaleVersionNeverChangeEitherFile() {
        val source = File(tempFolder.root, "source.md")
        source.writeText(MemoryParser.format("source", "d", "original"))
        val original = MemoryParser.parse(source, MemoryScope.GLOBAL)!!
        val target = File(tempFolder.root, "target.md").also { it.writeText("existing target") }
        val before = source.readText()
        assertFalse(MemoryFileStorage.renameChecked(original, original.copy(name = "target", content = "bad"), original.version))
        assertEquals(before, source.readText())
        assertEquals("existing target", target.readText())
        source.writeText(MemoryParser.format("source", "d", "external change"))
        val changed = source.readText()
        assertFalse(MemoryFileStorage.renameChecked(original, original.copy(name = "unused", content = "bad"), original.version))
        assertEquals(changed, source.readText())
        assertFalse(File(tempFolder.root, "unused.md").exists())
    }

    @Test fun renameFailureKeepsOriginalAndDoesNotPublishTarget() {
        val source = File(tempFolder.root, "source.md")
        source.writeText(MemoryParser.format("source", "d", "original"))
        val original = MemoryParser.parse(source, MemoryScope.GLOBAL)!!
        val before = source.readText()
        File(tempFolder.root, MemorySource.ARCHIVE_DIR).writeText("archive blocked")
        assertFalse(MemoryFileStorage.renameChecked(original, original.copy(name = "new-name", content = "bad"), original.version))
        assertEquals(before, source.readText())
        assertFalse(File(tempFolder.root, "new-name.md").exists())
        assertFalse(tempFolder.root.listFiles().orEmpty().any { it.name.startsWith(".memory-rename-") })
    }

}
