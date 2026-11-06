package com.aharou.feature.agent.domain.container

import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BuiltinAssetExtractorTest {
    private val root = Files.createTempDirectory("builtin-assets").toFile()

    @After fun cleanup() { root.deleteRecursively() }

    private fun assets(files: Map<String, String>, unreadable: String? = null): AssetManager {
        val assets = mockk<AssetManager>()
        every { assets.list(any()) } answers {
            val path = firstArg<String>().trimEnd('/')
            val prefix = if (path.isEmpty()) "" else "$path/"
            files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct().toTypedArray()
        }
        every { assets.open(any()) } answers {
            val name = firstArg<String>()
            if (name == unreadable) throw IOException("read failed")
            files.getValue(name).byteInputStream()
        }
        return assets
    }

    @Test fun builtinSkillResourcesAreDeployedWithoutOverwritingCustomSkills() {
        val custom = File(root, "skills/security-audit/SKILL.md").apply { requireNotNull(parentFile).mkdirs(); writeText("custom") }
        val source = assets(mapOf("skills/security-audit/scripts/audit.py" to "print('audit')",
            "skills/security-audit/references/checklist.md" to "check"))
        assertEquals(2, BuiltinAssetExtractor().extract(source, root, "skills"))
        assertEquals("print('audit')", File(root, "builtin-skills/security-audit/scripts/audit.py").readText())
        assertEquals("custom", custom.readText())
        assertEquals(0, BuiltinAssetExtractor().extract(source, root, "skills"))
    }

    @Test fun unchangedDocumentsWriteZeroFilesAcrossAppRestarts() {
        val source = assets(mapOf("docs/guide/a.md" to "same", "docs/advanced/b.md" to "other"))
        assertEquals(2, BuiltinAssetExtractor().extract(source, root, "docs"))
        val file = File(root, "docs/guide/a.md")
        assertTrue(file.setLastModified(1000))
        assertEquals(0, BuiltinAssetExtractor().extract(source, root, "docs"))
        assertEquals(1000, file.lastModified())
        assertEquals("same", file.readText())
    }

    @Test fun upgradeOnlyChangesDifferentDocumentsAndRemovesObsoleteBuiltinPaths() {
        BuiltinAssetExtractor().extract(assets(mapOf("docs/old.md" to "old", "docs/keep.md" to "same")), root, "docs")
        val keep = File(root, "docs/keep.md").apply { setLastModified(1000) }
        assertEquals(1, BuiltinAssetExtractor().extract(assets(mapOf("docs/new/a.md" to "new", "docs/keep.md" to "same")), root, "docs"))
        assertFalse(File(root, "docs/old.md").exists())
        assertEquals("new", File(root, "docs/new/a.md").readText())
        assertEquals(1000, keep.lastModified())
    }

    @Test fun partialAssetReadFailureLeavesExistingDocumentsIntactAndCanRetry() {
        BuiltinAssetExtractor().extract(assets(mapOf("docs/existing.md" to "valid")), root, "docs")
        val extractor = BuiltinAssetExtractor()
        try {
            extractor.extract(assets(mapOf("docs/new.md" to "new", "docs/broken.md" to "bad"), "docs/broken.md"), root, "docs")
            fail("must report unreadable asset")
        } catch (_: IOException) { }
        assertEquals("valid", File(root, "docs/existing.md").readText())
        assertFalse(File(root, "docs/new.md").exists())
        assertEquals(1, extractor.extract(assets(mapOf("docs/new.md" to "new")), root, "docs"))
        assertFalse(File(root, "docs/existing.md").exists())
    }

    @Test fun missingDocsNeverClearsExistingDocuments() {
        File(root, "docs").mkdirs()
        val existing = File(root, "docs/existing.md").apply { writeText("valid") }
        try { BuiltinAssetExtractor().extract(assets(emptyMap()), root, "docs"); fail("empty builtin docs must fail") }
        catch (_: IllegalStateException) { }
        assertEquals("valid", existing.readText())
    }

    @Test fun customPromptsAndUserMembersAreNotOverwrittenOrRemoved() {
        File(root, "prompts.custom").mkdirs()
        val custom = File(root, "prompts.custom/a.md").apply { writeText("custom") }
        File(root, "agents").mkdirs()
        val agent = File(root, "agents/explore.md").apply { writeText("edited") }
        val orphan = File(root, "agents/user.md").apply { writeText("mine") }
        val source = assets(mapOf("prompts/a.md" to "builtin", "agents/explore.md" to "default", "agents/new.md" to "new"))
        val extractor = BuiltinAssetExtractor()
        assertEquals(1, extractor.extract(source, root, "prompts"))
        assertEquals(0, extractor.extract(source, root, "prompts"))
        assertEquals(1, extractor.extract(source, root, "agents"))
        assertEquals("custom", custom.readText())
        assertEquals("edited", agent.readText())
        assertEquals("mine", orphan.readText())
    }

    @Test fun documentLinksCannotOverwriteFilesOutsideBuiltinDirectory() {
        val outside = File(root, "user.md").apply { writeText("private") }
        File(root, "docs").mkdirs()
        Files.createSymbolicLink(File(root, "docs/a.md").toPath(), outside.toPath())
        try { BuiltinAssetExtractor().extract(assets(mapOf("docs/a.md" to "replacement")), root, "docs"); fail("symlink must not be followed") }
        catch (_: IllegalStateException) { }
        assertEquals("private", outside.readText())
    }

    @Test fun failedExtractionDoesNotLeaveTemporaryFiles() {
        File(root, "prompts/a.md").mkdirs()
        val existing = File(root, "prompts/a.md/keep").apply { writeText("keep") }
        try {
            BuiltinAssetExtractor().extract(assets(mapOf("prompts/a.md" to "content")), root, "prompts")
            fail("replacing a nonempty directory with a file must fail")
        } catch (_: IOException) { }
        assertEquals("keep", existing.readText())
        assertTrue(root.walkTopDown().none { it.name.startsWith(".aharou-asset-") })
    }

    private fun hash(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun seedAssets(current: Map<String, String>, old: Map<String, String>): AssetManager {
        val entries = old.entries.joinToString(",") { (path, text) -> "\"$path\":[\"${hash(text)}\"]" }
        return assets(current + ("builtin-seed-history.json" to
            "{\"version\":1,\"sources\":[\"${"a".repeat(40)}\"],\"files\":{$entries}}"))
    }

    private fun put(path: String, text: String): File = File(root, path).apply {
        requireNotNull(parentFile).mkdirs()
        writeText(text)
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("unsafe seed operation must fail") }
        catch (_: IllegalStateException) { }
    }

    @Test fun historicalSeedsUpgradeForBothNamespacesWithoutChangingDisabledConfiguration() {
        for (directory in listOf("agents", "teams")) {
            val path = "$directory/nested/a.md"
            val file = put(path, "old")
            val disabled = put("agents.json", "{\"disabled\":[\"a\"]}")
            val source = seedAssets(mapOf(path to "new"), mapOf(path to "old"))
            assertEquals(1, BuiltinAssetExtractor().extract(source, root, directory))
            assertEquals("new", file.readText())
            assertEquals(setOf("a"), com.aharou.feature.agent.domain.subagent.AgentDefinitionConfigRepository.parseDisabled(disabled.readText()))
            assertTrue(File(root, "builtin-seed-state/$directory.json").readText().contains(hash("new")))
        }
    }

    @Test fun currentSeedIsRecordedWithoutRewritingAndRepeatedExtractionIsIdempotent() {
        val file = put("agents/a.md", "same").apply { assertTrue(setLastModified(1000)) }
        val source = seedAssets(mapOf("agents/a.md" to "same"), emptyMap())
        assertEquals(0, BuiltinAssetExtractor().extract(source, root, "agents"))
        val state = File(root, "builtin-seed-state/agents.json").apply { assertTrue(setLastModified(2000)) }
        assertEquals(0, BuiltinAssetExtractor().extract(source, root, "agents"))
        assertEquals(1000, file.lastModified())
        assertEquals(2000, state.lastModified())
    }

    @Test fun userEditedSameNameAndRetiredCustomFilesArePreserved() {
        val same = put("agents/a.md", "edited")
        val retired = put("agents/old.md", "also edited")
        val custom = put("agents/custom.md", "mine")
        val source = seedAssets(mapOf("agents/a.md" to "new"),
            mapOf("agents/a.md" to "old", "agents/old.md" to "original"))
        assertEquals(0, BuiltinAssetExtractor().extract(source, root, "agents"))
        assertEquals("edited", same.readText())
        assertEquals("also edited", retired.readText())
        assertEquals("mine", custom.readText())
        assertFalse(File(root, "builtin-archive").exists())
    }

    @Test fun retiredSeedIsMovedByteForByteAndCanBeRestored() {
        val original = put("teams/nested/old.md", "old\r\n原文\n")
        val bytes = original.readBytes()
        val source = seedAssets(mapOf("teams/new.md" to "new"), mapOf("teams/nested/old.md" to original.readText()))
        assertEquals(1, BuiltinAssetExtractor().extract(source, root, "teams"))
        val archive = File(root, "builtin-archive/teams/${hash("old\r\n原文\n")}/nested/old.md")
        assertFalse(original.exists())
        assertArrayEquals(bytes, archive.readBytes())
        assertEquals(0, BuiltinAssetExtractor().extract(source, root, "teams"))
        assertArrayEquals(bytes, archive.readBytes())
        Files.move(archive.toPath(), original.toPath())
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test fun localStateSupportsFutureUpgradeAndRetirementWithoutBundledHistory() {
        BuiltinAssetExtractor().extract(assets(mapOf("agents/a.md" to "v1")), root, "agents")
        assertEquals(1, BuiltinAssetExtractor().extract(assets(mapOf("agents/a.md" to "v2")), root, "agents"))
        val state = File(root, "builtin-seed-state/agents.json").readText()
        assertTrue(state.contains(hash("v1")))
        assertTrue(state.contains(hash("v2")))
        assertEquals(0, BuiltinAssetExtractor().extract(assets(emptyMap()), root, "agents"))
        assertEquals("v2", File(root, "builtin-archive/agents/${hash("v2")}/a.md").readText())
    }

    @Test fun missingHistoryNeverInfersOwnershipFromFilename() {
        val file = put("agents/a.md", "old")
        assertEquals(0, BuiltinAssetExtractor().extract(assets(mapOf("agents/a.md" to "new")), root, "agents"))
        assertEquals("old", file.readText())
        assertFalse(File(root, "builtin-seed-state/agents.json").exists())
    }

    @Test fun partialArchiveFailureAndRetryNeverOverwriteExistingBackup() {
        val a = put("agents/a.md", "a")
        val b = put("agents/b.md", "b")
        val aBackup = File(root, "builtin-archive/agents/${hash("a")}/a.md")
        val bBackup = put("builtin-archive/agents/${hash("b")}/b.md", "long-lived backup")
        val source = seedAssets(emptyMap(), linkedMapOf("agents/a.md" to "a", "agents/b.md" to "b"))
        repeat(2) { rejected { BuiltinAssetExtractor().extract(source, root, "agents") } }
        assertFalse(a.exists())
        assertEquals("a", aBackup.readText())
        assertEquals("b", b.readText())
        assertEquals("long-lived backup", bBackup.readText())
        assertTrue(root.walkTopDown().none { it.name.startsWith(".aharou-asset-") })
    }

    @Test fun identicalArchiveConflictStillPreservesSourceAndBackup() {
        val sourceFile = put("teams/a.md", "old")
        val archive = put("builtin-archive/teams/${hash("old")}/a.md", "old")
        rejected { BuiltinAssetExtractor().extract(seedAssets(emptyMap(), mapOf("teams/a.md" to "old")), root, "teams") }
        assertEquals("old", sourceFile.readText())
        assertEquals("old", archive.readText())
    }

    @Test fun memberFileLinkCannotBeUpgradedOrArchived() {
        val outside = put("user.md", "old")
        File(root, "agents").mkdirs()
        Files.createSymbolicLink(File(root, "agents/a.md").toPath(), outside.toPath())
        val history = mapOf("agents/a.md" to "old")
        rejected { BuiltinAssetExtractor().extract(seedAssets(mapOf("agents/a.md" to "new"), history), root, "agents") }
        rejected { BuiltinAssetExtractor().extract(seedAssets(emptyMap(), history), root, "agents") }
        assertEquals("old", outside.readText())
    }

    @Test fun archiveAndStateParentLinksCannotEscapeConfigDirectory() {
        val outside = File(root, "outside").apply { mkdirs() }
        val old = put("agents/a.md", "old")
        Files.createSymbolicLink(File(root, "builtin-archive").toPath(), outside.toPath())
        rejected { BuiltinAssetExtractor().extract(seedAssets(emptyMap(), mapOf("agents/a.md" to "old")), root, "agents") }
        assertEquals("old", old.readText())
        Files.createSymbolicLink(File(root, "builtin-seed-state").toPath(), outside.toPath())
        rejected { BuiltinAssetExtractor().extract(assets(mapOf("agents/a.md" to "new")), root, "agents") }
        assertTrue(outside.listFiles().orEmpty().isEmpty())
        assertEquals("old", old.readText())
    }

    @Test fun linkedConfigRootAndDirectoryTargetsAreRejected() {
        val actual = File(root, "actual").apply { mkdirs() }
        val link = File(root, "config-link")
        Files.createSymbolicLink(link.toPath(), actual.toPath())
        rejected { BuiltinAssetExtractor().extract(assets(mapOf("teams/a.md" to "new")), link, "teams") }
        File(root, "teams/a.md").mkdirs()
        rejected { BuiltinAssetExtractor().extract(assets(mapOf("teams/a.md" to "new")), root, "teams") }
        assertTrue(actual.listFiles().orEmpty().isEmpty())
    }

    @Test fun traversalInHistoricalPathCannotTouchFilesOutsideNamespace() {
        val outside = put("user.md", "private")
        try {
            BuiltinAssetExtractor().extract(seedAssets(emptyMap(), mapOf("agents/../user.md" to "private")), root, "agents")
            fail("traversal must fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals("private", outside.readText())
    }

    @Test fun unreadableHistoryAbortsBeforeAnySeedChangesAndRetrySucceeds() {
        val old = put("teams/a.md", "old")
        val source = assets(mapOf("teams/a.md" to "new", "builtin-seed-history.json" to "{}"), "builtin-seed-history.json")
        try { BuiltinAssetExtractor().extract(source, root, "teams"); fail("history read failure must surface") }
        catch (_: IOException) { }
        assertEquals("old", old.readText())
        assertEquals(1, BuiltinAssetExtractor().extract(seedAssets(mapOf("teams/a.md" to "new"), mapOf("teams/a.md" to "old")), root, "teams"))
    }
}
