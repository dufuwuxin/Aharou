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
            val prefix = firstArg<String>().trimEnd('/') + "/"
            files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct().toTypedArray()
        }
        every { assets.open(any()) } answers {
            val name = firstArg<String>()
            if (name == unreadable) throw IOException("read failed")
            files.getValue(name).byteInputStream()
        }
        return assets
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
}
