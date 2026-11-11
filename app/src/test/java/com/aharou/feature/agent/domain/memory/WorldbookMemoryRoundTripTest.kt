package com.aharou.feature.agent.domain.memory

import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.context.WorldbookAudience
import com.aharou.feature.agent.domain.context.WorldbookRelation
import com.aharou.feature.agent.domain.context.WorldbookRelationType
import com.aharou.feature.agent.domain.context.WorldbookRules
import com.aharou.feature.settings.presentation.ContextEditorDraft
import com.aharou.core.memory.AharouMemoryStore
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorldbookMemoryRoundTripTest {
    @get:Rule val temp = TemporaryFolder()
    private val rules = WorldbookRules(triggers = listOf("Android", "a\"b\nnext"), excludes = listOf("ignore"),
        matchAll = true, caseSensitive = true, priority = 9, roles = listOf("reviewer"),
        audiences = listOf(WorldbookAudience.ROLE), confidence = 0.6, importance = 80,
        relations = listOf(WorldbookRelation("target-id", WorldbookRelationType.SUPPORTS)))

    private fun create(root: File, name: String = "entry", metadata: WorldbookRules = rules): Memory {
        val file = File(root, "$name.md")
        file.writeText(MemoryParser.format(Memory(name, "summary", MemoryScope.GLOBAL, content = "old body",
            worldbook = metadata, contextLoad = ContextLoad.FULL)))
        return requireNotNull(MemoryParser.parse(file, MemoryScope.GLOBAL))
    }

    @Test fun nativeFrontmatterRoundTripIncludesAllFieldsAndEscapedTerms() {
        val memory = create(temp.newFolder())
        assertEquals(rules, memory.worldbook)
        assertTrue(requireNotNull(memory.file).readText().substringBefore("\n---\n").contains("worldbook:"))
        assertEquals("old body", memory.content)
        assertEquals(memory.worldbook, WorldbookRules.fromJson(memory.worldbook.toJson()))
    }

    @Test fun oldFilesRetainOriginalBehaviorWithoutWorldbookMetadata() {
        val file = temp.newFile("legacy.md")
        file.writeText("---\nname: legacy\ndescription: old summary\n---\nold body")
        val old = requireNotNull(MemoryParser.parse(file, MemoryScope.GLOBAL))
        assertEquals(WorldbookRules(), old.worldbook)
        assertEquals(ContextLoad.INDEX, old.contextLoad)
        assertEquals(ContextCategory.LONG_TERM, old.contextCategory)
        assertFalse(old.hasContextMetadata)
        assertNull(old.worldbook.omission("", WorldbookAudience.MAIN, null))
        assertFalse(MemoryParser.format(old).contains("worldbook:"))
    }

    @Test fun ordinarySavePreservesMetadataAndStaleWriteCannotReplaceIt() {
        val root = temp.newFolder()
        val old = create(root)
        assertEquals(MemoryWriteResult.Success, MemoryFileStorage.saveChecked(root, MemoryScope.GLOBAL,
            old.name, old.description, "new body", null, null, null, null, old.version))
        val current = requireNotNull(MemoryParser.parse(requireNotNull(old.file), MemoryScope.GLOBAL))
        assertEquals(rules, current.worldbook)
        assertEquals("new body", current.content)
        assertTrue(MemoryFileStorage.saveChecked(root, MemoryScope.GLOBAL, old.name, old.description,
            "stale", null, null, null, null, old.version, worldbook = WorldbookRules()) is MemoryWriteResult.VersionConflict)
        assertEquals(current, MemoryParser.parse(requireNotNull(old.file), MemoryScope.GLOBAL))
        assertEquals(MemoryWriteResult.Success, MemoryFileStorage.saveChecked(root, MemoryScope.GLOBAL,
            old.name, old.description, current.content, null, null, null, null, current.version, worldbook = WorldbookRules()))
        assertEquals(WorldbookRules(), MemoryParser.parse(requireNotNull(old.file), MemoryScope.GLOBAL)?.worldbook)
    }

    @Test fun renameAndFolderMetadataRewritePreserveWorldbook() {
        val root = temp.newFolder()
        val old = create(root)
        assertTrue(MemoryFileStorage.renameChecked(old, old.copy(name = "renamed"), old.version))
        val renamed = requireNotNull(MemoryParser.parse(File(root, "renamed.md"), MemoryScope.GLOBAL))
        assertEquals(rules, renamed.worldbook)
        assertTrue(MemoryFileStorage.rewriteContextMetadata(listOf(renamed.copy(contextFolder = "nested/folder"))))
        val updated = requireNotNull(MemoryParser.parse(File(root, "renamed.md"), MemoryScope.GLOBAL))
        assertEquals("nested/folder", updated.contextFolder)
        assertEquals(rules, updated.worldbook)
    }

    @Test fun incompatibleMetadataCannotBeMergedAndCompatibleMergePreservesIt() {
        val root = temp.newFolder()
        val a = create(root, "first")
        val b = create(root, "second", rules.copy(priority = 10))
        assertFalse(MemoryFileStorage.merge(listOf(a, b), "merged", "summary", "body"))
        assertTrue(requireNotNull(a.file).isFile)
        assertTrue(requireNotNull(b.file).isFile)
        val c = create(root, "third")
        assertTrue(MemoryFileStorage.merge(listOf(a, c), "merged", "summary", "body"))
        assertEquals(rules, MemoryParser.parse(File(root, "merged.md"), MemoryScope.GLOBAL)?.worldbook)
    }

    @Test fun sourcePartialEditPreservesRulesAndRejectsStaleVersion() {
        val directory = temp.newFolder()
        val original = create(directory)
        val store = io.mockk.mockk<AharouMemoryStore>()
        io.mockk.every { store.dir } returns directory
        val source = GlobalMemorySource(store)
        assertEquals(MemoryEditResult.Success, source.editMemory(original.name,
            listOf(MemoryEdit("old body", "edited body", false)), original.version))
        val edited = requireNotNull(MemoryParser.parse(requireNotNull(original.file), MemoryScope.GLOBAL))
        assertEquals("edited body", edited.content)
        assertEquals(rules, edited.worldbook)
        assertTrue(source.editMemory(original.name, listOf(MemoryEdit("edited body", "stale", false)),
            original.version) is MemoryEditResult.VersionConflict)
        assertEquals(edited, MemoryParser.parse(requireNotNull(original.file), MemoryScope.GLOBAL))
    }

    @Test fun editorDraftRoundTripPreservesValidAndInvalidRuleTextAndOldDraftDefaults() {
        val draft = ContextEditorDraft.from(null, ContextCategory.LONG_TERM, "", false)
            .copy(worldbookJson = rules.toJson())
        assertEquals(draft, ContextEditorDraft.restore(draft.savedFields()))
        assertEquals(rules, WorldbookRules.fromJson(ContextEditorDraft.restore(draft.savedFields()).worldbookJson))
        val invalid = draft.copy(worldbookJson = "{unfinished")
        assertEquals(invalid, ContextEditorDraft.restore(invalid.savedFields()))
        assertTrue(runCatching { WorldbookRules.fromJson(invalid.worldbookJson) }.isFailure)
        val legacy = ContextEditorDraft.restore(draft.savedFields().take(10))
        assertEquals(WorldbookRules(), WorldbookRules.fromJson(legacy.worldbookJson))
        assertEquals(draft.name, legacy.name)
        assertEquals(draft.content, legacy.content)
    }

    @Test fun invalidNativeWorldbookDoesNotSilentlyBecomeAlwaysActive() {
        val file = temp.newFile("bad.md")
        file.writeText("---\nname: bad\nworldbook: '{\"confidence\":2}'\n---\nbody")
        val memory = requireNotNull(MemoryParser.parse(file, MemoryScope.GLOBAL))
        assertFalse(memory.worldbook.enabled)
        assertNotNull(memory.worldbook.omission("anything", WorldbookAudience.MAIN, null))
    }
}
