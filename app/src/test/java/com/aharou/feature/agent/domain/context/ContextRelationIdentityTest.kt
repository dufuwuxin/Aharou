package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.*
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextRelationIdentityTest {
    @get:Rule val temp = TemporaryFolder()
    private val memories = mockk<MemoryRepository>()
    private lateinit var global: File
    private lateinit var project: File
    private lateinit var library: ContextLibraryRepository

    @Before fun setup() {
        global = temp.newFolder("global")
        project = temp.newFolder("project")
        every { memories.listMemoriesInScope(any(), any()) } answers {
            val scope = firstArg<MemoryScope>()
            directory(scope).listFiles().orEmpty().filter { it.extension == "md" }
                .mapNotNull { MemoryParser.parse(it, scope) }
        }
        every { memories.writeMemory(any(), any(), any(), any(), any(), expectedVersion = any(),
            contextKind = any(), contextLoad = any(), contextCategory = any(), contextFolder = any(),
            contextSessionId = any(), contextRequest = any(), worldbook = any()) } answers {
            MemoryFileStorage.saveChecked(directory(arg(3)), arg(3), arg(0), arg(1), arg(2),
                arg(5), arg(6), arg(7), arg(8), arg(9), arg(10), arg(11), arg(12), arg(13), arg(14), arg(15), arg(16))
        }
        reload()
    }

    private fun reload() { library = ContextLibraryRepository(memories) }
    private fun directory(scope: MemoryScope) = if (scope == MemoryScope.GLOBAL) global
        else File(project, ".aharou/memory").also { it.mkdirs() }
    private fun entries() = library.list(project.path)
    private fun entry(name: String, scope: MemoryScope = MemoryScope.GLOBAL) = entries().single { it.title == name && it.scope == scope }
    private fun create(name: String, target: String? = null, scope: MemoryScope = MemoryScope.GLOBAL) {
        assertTrue(library.create(name, "summary", "body-$name", scope, ContextKind.REFERENCE, ContextLoad.SEARCH,
            project.path, worldbook = rules(target)))
    }
    private fun rules(target: String?) = WorldbookRules(tags = listOf("identity"),
        relations = target?.let { listOf(WorldbookRelation(it)) }.orEmpty())
    private fun edge() = ContextMemoryGraph.project(entries()).edges.single { it.sourceId == entry("A").id }
    private fun legacy(name: String, target: String? = null) {
        File(global, "$name.md").writeText(MemoryParser.format(name, "summary", "body-$name", worldbook = rules(target)))
    }

    @Test fun savedRelationSurvivesRenameReloadAndOldTitleReuse() {
        create("B")
        create("A", "B")
        val original = entry("B")
        val stable = original.graphId
        assertEquals(stable, entry("A").worldbook.relations.single().target)
        assertTrue(library.save(original.copy(title = "Renamed"), project.path))
        reload()
        assertEquals(stable, entry("Renamed").graphId)
        assertEquals(entry("Renamed").id, edge().targetId)
        create("B")
        reload()
        assertNotEquals(stable, entry("B").graphId)
        assertEquals(entry("Renamed").id, edge().targetId)
        assertNotEquals(original.id, entry("Renamed").id)
    }

    @Test fun legacyTitleReferenceKeepsIdentityWhenOldTitleIsReused() {
        legacy("B")
        legacy("A", "B")
        val before = entry("B")
        assertTrue(library.save(before.copy(title = "Renamed"), project.path))
        create("B")
        reload()
        assertEquals(before.graphId, entry("Renamed").graphId)
        assertEquals(entry("Renamed").id, edge().targetId)
    }

    @Test fun renamingTheReusedTitleCannotClaimAnotherEntitysHistoricalAlias() {
        legacy("B")
        legacy("A", "B")
        assertTrue(library.save(entry("B").copy(title = "C"), project.path))
        val originalId = entry("C").graphId
        create("B")
        assertTrue(library.save(entry("B").copy(title = "D"), project.path))
        reload()
        assertEquals(originalId, entry("C").graphId)
        assertEquals(entry("C").id, edge().targetId)
        assertFalse("B" in entry("D").graphAliases)
    }

    @Test fun legacyPublicIdReferenceCannotBeStolenByNewEntity() {
        legacy("B")
        val before = entry("B")
        legacy("A", before.id)
        assertTrue(library.save(before.copy(title = "Renamed"), project.path))
        create("B")
        reload()
        assertEquals(entry("Renamed").id, edge().targetId)
    }

    @Test fun relationToUnsavedLegacyTargetIsStableWithoutWritingTargetOnRead() {
        legacy("B")
        val file = File(global, "B.md")
        val bytes = file.readBytes()
        val target = entry("B")
        assertArrayEquals(bytes, file.readBytes())
        create("A", "B")
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(library.save(target.copy(title = "Renamed"), project.path))
        reload()
        assertEquals(entry("Renamed").id, edge().targetId)
    }

    @Test fun globalAndProjectSameTitleStayAmbiguousEvenWithHistoricalAlias() {
        create("B")
        create("B", scope = MemoryScope.PROJECT)
        create("A", "B")
        assertEquals(ContextMemoryResolution.AMBIGUOUS, edge().resolution)
        assertTrue(library.save(entry("B").copy(title = "Renamed"), project.path))
        reload()
        assertEquals(ContextMemoryResolution.AMBIGUOUS, edge().resolution)
        assertNull(edge().targetId)
    }

    @Test fun projectIdentityIsBoundToWorkspaceAndMalformedReservedIdNeverFallsBackToTitle() {
        create("B", scope = MemoryScope.PROJECT)
        val target = entry("B", MemoryScope.PROJECT)
        create("A", target.graphId)
        val other = target.copy(workspaceRoot = temp.newFolder("other").path,
            graphId = target.graphId.replace(Regex("PROJECT:[0-9a-f]{64}"), "PROJECT:${"0".repeat(64)}"))
        assertEquals(ContextMemoryResolution.MISSING,
            ContextMemoryGraph.project(listOf(entry("A"), other)).edges.single().resolution)
        val invalid = "graph:v1:PROJECT:../../GLOBAL:bad"
        legacy("invalid", invalid)
        create("named-invalid")
        val nodes = entries().map { if (it.title == "named-invalid") it.copy(title = invalid) else it }
        assertEquals(ContextMemoryResolution.MISSING, ContextMemoryGraph.project(nodes).edges
            .single { it.sourceId == entry("invalid").id }.resolution)
    }

    @Test fun ordinarySavePartialEditAndRuleResetPreserveIdentityAndAliases() {
        create("B")
        assertTrue(library.save(entry("B").copy(title = "Renamed"), project.path))
        val before = MemoryParser.parse(File(global, "Renamed.md"), MemoryScope.GLOBAL)!!
        assertEquals(MemoryWriteResult.Success, MemoryFileStorage.saveChecked(global, MemoryScope.GLOBAL,
            before.name, "summary", "body-new", null, null, null, null, before.version, worldbook = WorldbookRules()))
        val store = mockk<com.aharou.core.memory.AharouMemoryStore>()
        every { store.dir } returns global
        val source = GlobalMemorySource(store)
        val saved = MemoryParser.parse(before.file!!, MemoryScope.GLOBAL)!!
        assertEquals(MemoryEditResult.Success, source.editMemory(saved.name,
            listOf(MemoryEdit("body-new", "body-edited", false)), saved.version))
        val edited = MemoryParser.parse(requireNotNull(before.file), MemoryScope.GLOBAL)!!
        assertEquals(before.graphId, edited.graphId)
        assertEquals(before.graphAliases, edited.graphAliases)
        assertEquals(WorldbookRules(), edited.worldbook)
    }

    @Test fun staleRenameAndOccupiedTargetDoNotPartiallyWriteIdentity() {
        legacy("B")
        create("C")
        val before = entry("B")
        val file = File(global, "B.md")
        val original = file.readBytes()
        assertFalse(library.save(before.copy(title = "C"), project.path))
        assertArrayEquals(original, file.readBytes())
        file.appendText("external")
        val changed = file.readBytes()
        assertFalse(library.save(before.copy(title = "Renamed"), project.path))
        assertArrayEquals(changed, file.readBytes())
        assertFalse(File(global, "Renamed.md").exists())
    }

    @Test fun repeatedRenameRetainsEveryHistoricalReference() {
        legacy("B")
        legacy("A", "B")
        assertTrue(library.save(entry("B").copy(title = "Second"), project.path))
        reload()
        assertTrue(library.save(entry("Second").copy(title = "Third"), project.path))
        create("B")
        reload()
        assertEquals(entry("Third").id, edge().targetId)
        assertTrue(entry("Third").graphAliases.containsAll(listOf("B", "Second")))
    }
}
