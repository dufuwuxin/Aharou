package com.aharou.feature.agent.domain.memory

import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryStorageFolderBoundaryTest {
    @get:Rule val temp = TemporaryFolder()

    @After fun resetFaultInjection() {
        MemoryFileStorage.beforeMetadataWrite = {}
        MemoryFileStorage.beforeMetadataCommit = {}
        MemoryFileStorage.resetFolderJournal()
    }

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

    @Test fun legacyMigrationAndFolderMovesPreserveExactBodyBytesAndUserMetadata() {
        val file = MemorySource.resolveMemoryFile(temp.root, "custom")
        val payload = MemoryParser.format("改名", "用户摘要", "body", contextCategory = com.aharou.feature.agent.domain.context.ContextCategory.RULE,
            contextFolder = "requests/agent/custom.md", contextLoad = com.aharou.feature.agent.domain.context.ContextLoad.OFF,
            pending = true, expiresAt = 1L)
        val body = "  用户正文\r\ncontext_folder: body-only\r\ncontext_request: body-only\r\n\r\n尾部空格  \r\n\r\n"
        file.writeText(payload.substringBeforeLast("---\n").replace("\n", "\r\n") + "---\r\n" + body)
        val original = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        val migrated = MemoryFileStorage.migrateContextLayout(listOf(original)).single()
        assertEquals("辅助请求", migrated.contextFolder)
        assertEquals("agent/custom.md", migrated.contextRequest)
        assertTrue(migrated.pending)
        assertEquals(1L, migrated.expiresAt)
        assertEquals(com.aharou.feature.agent.domain.context.ContextLoad.OFF, migrated.contextLoad)
        assertEquals(body, file.readText().substringAfterLast("---\r\n"))
        assertTrue(MemoryFileStorage.rewriteContextMetadata(listOf(migrated.copy(contextFolder = ""))))
        assertEquals(body, file.readText().substringAfterLast("---\r\n"))
        assertEquals("agent/custom.md", MemoryParser.parse(file, MemoryScope.GLOBAL)!!.contextRequest)
        assertEquals("", MemoryParser.parse(file, MemoryScope.GLOBAL)!!.contextFolder)
    }

    @Test fun requestKeySurvivesOrdinarySaveEditAndRename() {
        val file = MemorySource.resolveMemoryFile(temp.root, "entry")
        file.writeText(MemoryParser.format("entry", "说明", "before", contextRequest = "agent/custom.md"))
        assertEquals(MemoryWriteResult.Success, MemoryFileStorage.saveChecked(temp.root, MemoryScope.GLOBAL,
            "entry", "说明", "saved", null, null, null, null, MemoryParser.parse(file, MemoryScope.GLOBAL)!!.version))
        val source = object : MemorySource {
            override val root: java.io.File get() = temp.root
            override fun loadContent(name: String): String? = listMemories().firstOrNull { it.name == name }?.content
            override fun listMemories() = listOfNotNull(MemoryParser.parse(file, MemoryScope.GLOBAL))
            override fun saveMemory(name: String, description: String, content: String, type: MemoryType?,
                expiresAt: Long?, pending: Boolean?, keywords: List<String>?) = false
            override fun deleteMemory(name: String) = false
            override fun archiveContent(name: String, description: String, content: String, type: MemoryType?,
                keywords: List<String>, expiresAt: Long?, pending: Boolean) = false
        }
        val saved = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        assertEquals("agent/custom.md", saved.contextRequest)
        assertEquals(MemoryEditResult.Success, source.editMemory("entry", listOf(MemoryEdit("saved", "edited", false)), saved.version))
        val edited = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        assertTrue(MemoryFileStorage.renameChecked(edited, edited.copy(name = "renamed", contextFolder = "任意"), edited.version))
        val renamed = MemoryParser.parse(MemorySource.resolveMemoryFile(temp.root, "renamed"), MemoryScope.GLOBAL)!!
        assertEquals("agent/custom.md", renamed.contextRequest)
        assertEquals("edited", renamed.content)
    }


    @Test fun legacyInjectionDirectoriesMergeWithoutTouchingContentOrDeletingEntries() {
        val categories = listOf(com.aharou.feature.agent.domain.context.ContextCategory.ROLE_INJECTED,
            com.aharou.feature.agent.domain.context.ContextCategory.MAIN_INJECTED)
        for (category in categories) {
            val file = MemorySource.resolveMemoryFile(temp.root, category.name)
            file.writeText(MemoryParser.format(category.name, "用户摘要", "用户正文", contextCategory = category,
                contextFolder = "内置默认", contextLoad = com.aharou.feature.agent.domain.context.ContextLoad.OFF))
            val old = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
            val migrated = MemoryFileStorage.migrateContextLayout(listOf(old)).single()
            assertEquals(if (category == categories.first()) "角色注入" else "主代理注入", migrated.contextFolder)
            assertEquals("用户正文", migrated.content)
            assertEquals(old.contextLoad, migrated.contextLoad)
            val bytes = file.readText()
            MemoryFileStorage.migrateContextLayout(listOf(migrated))
            assertEquals(bytes, file.readText())
        }
    }

    @Test fun existingRequestKeyStillMigratesLegacyFolderAndSecondRunIsStable() {
        val file = MemorySource.resolveMemoryFile(temp.root, "existing-key")
        file.writeText(MemoryParser.format("existing-key", "说明", "body", contextCategory = com.aharou.feature.agent.domain.context.ContextCategory.RULE,
            contextFolder = "requests/agent/existing.md", contextRequest = "agent/existing.md"))
        val migrated = MemoryFileStorage.migrateContextLayout(listOf(MemoryParser.parse(file, MemoryScope.GLOBAL)!!)).single()
        assertEquals("辅助请求", migrated.contextFolder)
        assertEquals("agent/existing.md", migrated.contextRequest)
        val bytes = file.readText()
        MemoryFileStorage.migrateContextLayout(listOf(migrated))
        assertEquals(bytes, file.readText())
    }

    @Test fun interruptedMetadataOperationRestoresOriginalBytesBeforeRetry() {
        val first = MemorySource.resolveMemoryFile(temp.root, "first")
        val second = MemorySource.resolveMemoryFile(temp.root, "second")
        for ((index, file) in listOf(first, second).withIndex()) {
            file.writeText(MemoryParser.format("entry-$index", "说明", "body-$index", contextFolder = "旧/$index"))
        }
        val original = listOf(first, second).map { MemoryParser.parse(it, MemoryScope.GLOBAL)!! }
        val bytes = listOf(first, second).map { it.readText() }
        var injected = 0
        MemoryFileStorage.beforeMetadataWrite = { file ->
            if (file == second && injected++ == 0) {
                file.writeText("simulated-interrupt")
                throw RuntimeException("simulated metadata interruption")
            }
        }
        val updates = original.map { it.copy(contextFolder = "新") }
        assertFalse(MemoryFileStorage.rewriteContextMetadata(updates))
        assertEquals(bytes, listOf(first, second).map { it.readText() })
        assertTrue(temp.root.listFiles().orEmpty().none { it.name.startsWith(".context-layout-restore-") })
        MemoryFileStorage.beforeMetadataWrite = {}
        assertTrue(MemoryFileStorage.rewriteContextMetadata(original.map { it.copy(contextFolder = "新") }))
        assertEquals(listOf("新", "新"), listOf(first, second).map { MemoryParser.parse(it, MemoryScope.GLOBAL)!!.contextFolder })
    }

    @Test fun committedFolderDeletionRecoversWithoutMovingEntriesBack() {
        val file = MemorySource.resolveMemoryFile(temp.root, "committed")
        file.writeText(MemoryParser.format("committed", "说明", "body", contextFolder = "旧/子"))
        val original = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        MemoryFileStorage.folderJournal = ContextMetadataRecovery(temp.root, { "" }, { text ->
            File(temp.root, "marker").writeText(text)
            true
        })
        var commitFaults = 0
        MemoryFileStorage.beforeMetadataCommit = { commitFaults++; throw RuntimeException("simulated post-marker interruption") }
        assertFalse(MemoryFileStorage.rewriteContextMetadata(listOf(original.copy(contextFolder = "旧")),
            finish = { true }))
        assertEquals(1, commitFaults)
        assertEquals("旧", MemoryParser.parse(file, MemoryScope.GLOBAL)!!.contextFolder)
        val journal = File(temp.root.parentFile, ".context-layout-restore.json")
        assertTrue(journal.isFile)
        assertTrue(org.json.JSONObject(journal.readText()).getBoolean("committed"))
        assertTrue(MemoryFileStorage.folderJournal.recover())
        val saved = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        assertEquals("旧", saved.contextFolder)
        assertEquals("body", saved.content)
        assertTrue(MemoryFileStorage.folderJournal.recover())
    }

    @Test fun coldRecoveryUsesPersistedCommitAndKeepsCommittedMarker() {
        val file = MemorySource.resolveMemoryFile(temp.root, "cold-committed")
        file.writeText(MemoryParser.format("cold-committed", "说明", "body", contextFolder = "旧/子"))
        val original = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        val marker = File(temp.root, "marker").apply { writeText("old-marker") }
        fun recovery() = ContextMetadataRecovery(marker, { marker.readText() }, { text ->
            marker.writeText(text)
            true
        })
        MemoryFileStorage.folderJournal = recovery()
        var commitFaults = 0
        MemoryFileStorage.beforeMetadataCommit = { commitFaults++; throw RuntimeException("simulated post-commit interruption") }
        assertFalse(MemoryFileStorage.rewriteContextMetadata(listOf(original.copy(contextFolder = "新")),
            finish = {
                marker.writeText("new-marker")
                true
            }))
        assertEquals(1, commitFaults)
        assertEquals("新", MemoryParser.parse(file, MemoryScope.GLOBAL)!!.contextFolder)
        assertEquals("new-marker", marker.readText())
        val journal = File(temp.root, ".context-layout-restore.json")
        assertTrue(journal.isFile)
        assertTrue(org.json.JSONObject(journal.readText()).getBoolean("committed"))
        assertTrue(recovery().recover())
        val saved = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        assertEquals("新", saved.contextFolder)
        assertEquals("body", saved.content)
        assertEquals("new-marker", marker.readText())
        assertFalse(File(temp.root, ".context-layout-restore.json").exists())
    }

    @Test fun emptyFolderCanDeleteWithoutRequiringMetadataJournal() {
        assertTrue(MemoryFileStorage.rewriteContextMetadata(emptyList(), finish = { true }))
    }

    @Test fun legacyRequestRootMovesWithoutGuessingARouteFromTitleOrBody() {
        val file = MemorySource.resolveMemoryFile(temp.root, "root")
        file.writeText(MemoryParser.format("root", "说明", "context_request: body-only",
            contextCategory = com.aharou.feature.agent.domain.context.ContextCategory.RULE, contextFolder = "requests"))
        val migrated = MemoryFileStorage.migrateContextLayout(listOf(MemoryParser.parse(file, MemoryScope.GLOBAL)!!)).single()
        assertEquals("辅助请求", migrated.contextFolder)
        assertNull(migrated.contextRequest)
        assertEquals("context_request: body-only", migrated.content)
    }
}
