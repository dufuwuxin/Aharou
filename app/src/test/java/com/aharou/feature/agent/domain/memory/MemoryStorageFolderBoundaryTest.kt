package com.aharou.feature.agent.domain.memory

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryStorageFolderBoundaryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun directCreateRejectsInvalidFolderBeforePublishingAFile() {
        listOf("/资料", "资料/", "资料//接口", "../资料", "资料/..", "资料\\接口").forEach { folder ->
            val result = MemoryFileStorage.saveChecked(temp.root, MemoryScope.GLOBAL, "entry", "说明", "正文",
                null, null, null, null, MemorySource.VERSION_NEW, contextFolder = folder)
            assertTrue("must reject $folder, got $result", result is MemoryWriteResult.Failure)
            assertFalse(MemorySource.resolveMemoryFile(temp.root, "entry").exists())
        }
    }

    @Test fun directUpdateRejectsInvalidFolderWithoutChangingOriginalBytes() {
        val file = MemorySource.resolveMemoryFile(temp.root, "entry")
        file.writeText(MemoryParser.format("entry", "说明", "before", contextFolder = "资料/接口"))
        val before = file.readText()
        val version = MemoryParser.parse(file, MemoryScope.GLOBAL)!!.version
        val result = MemoryFileStorage.saveChecked(temp.root, MemoryScope.GLOBAL, "entry", "说明", "bad update",
            null, null, null, null, version, contextFolder = "/资料")
        assertTrue(result is MemoryWriteResult.Failure)
        assertEquals(before, file.readText())
    }

    @Test fun directRenameRejectsInvalidFolderAndLeavesBothPathsUntouched() {
        val file = MemorySource.resolveMemoryFile(temp.root, "entry")
        file.writeText(MemoryParser.format("entry", "说明", "before", contextFolder = "资料"))
        val before = file.readText()
        val original = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        assertFalse(MemoryFileStorage.renameChecked(original, original.copy(name = "renamed", contextFolder = "资料//接口"), original.version))
        assertEquals(before, file.readText())
        assertFalse(MemorySource.resolveMemoryFile(temp.root, "renamed").exists())
    }
}
