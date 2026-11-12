package com.aharou.feature.plugin

import com.aharou.feature.plugin.domain.market.OperitAsset
import com.aharou.feature.plugin.domain.market.OperitCatalog
import com.aharou.feature.plugin.domain.market.OperitEntry
import com.aharou.feature.plugin.domain.market.OperitGitHub
import com.aharou.feature.plugin.domain.market.OperitMarketRepository
import com.aharou.feature.plugin.domain.market.OperitVersion
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OperitMarketTest {
    private val page = """{"ok":true,"marketVersion":2,"generatedAt":"2026-11-12T00:00:00Z","sort":"updated","page":1,"pageSize":100,"total":101,"items":[{"id":"fixture","type":"skill","title":"Fixture","extra":"ignored"}]}"""

    @Test fun parsesMissingAssetsAndUnknownFields() {
        val parsed = OperitCatalog.parse(page, "skill", 1)
        assertTrue(parsed.hasNext)
        assertTrue(parsed.items.single().latestAssets.isEmpty())
        assertEquals(null, parsed.items.single().source)
        assertEquals(0, OperitCatalog.filter(parsed.items, "absent").size)
        assertEquals(1, OperitCatalog.filter(parsed.items, "fixture").size)
    }

    @Test fun rejectsFailureWrongPageTypeAndVersion() {
        rejected { OperitCatalog.parse(page.replace("\"ok\":true", "\"ok\":false"), "skill", 1) }
        rejected { OperitCatalog.parse(page, "skill", 2) }
        rejected { OperitCatalog.parse(page, "package", 1) }
        rejected { OperitCatalog.parse(page.replace("\"marketVersion\":2", "\"marketVersion\":3"), "skill", 1) }
        rejected { OperitCatalog.path("../skill", 1) }
        rejected { OperitCatalog.path("skill", 0) }
        assertEquals("lists/all/updated/page-2.json", OperitCatalog.path("all", 2))
        assertEquals("lists/type/skill/updated/page-2.json", OperitCatalog.path("skill", 2))
        assertFalse(OperitCatalog.parse(page.replace("\"total\":101", "\"total\":100"), "skill", 1).hasNext)
    }

    @Test fun assetsMustMatchLatestVersionNotDisplayVersion() {
        val old = OperitAsset("old", "v1", url = "https://github.com/o/r/old.zip")
        val latest = OperitAsset("new", "v2", url = "https://github.com/o/r/new.zip")
        val entry = OperitEntry("a", "skill", latestVersion = OperitVersion("v2", "1.0"), assets = listOf(old, latest))
        assertEquals(listOf(latest), entry.latestAssets)
        assertTrue(entry.copy(latestVersion = null).latestAssets.isEmpty())
        assertTrue(entry.copy(assets = listOf(old)).latestAssets.isEmpty())
        assertTrue(entry.copy(latestVersion = OperitVersion()).latestAssets.isEmpty())
    }

    @Test fun validatesChecksumAndMissingChecksum() {
        val digest = MessageDigest.getInstance("SHA-256").digest("fixture".toByteArray())
        OperitMarketRepository.verifyHash(digest, digest.joinToString("") { "%02x".format(it) })
        OperitMarketRepository.verifyHash(digest, null)
        rejected { OperitMarketRepository.verifyHash(digest, "0".repeat(64)) }
        rejected { OperitMarketRepository.verifyHash(digest, "invalid") }
    }

    @Test fun parsesExactSourceSubtreeWithoutGuessingDefaultBranch() {
        val parsed = OperitGitHub.parse("https://github.com/Zon-dev-max/operit-executor-kit/tree/main/skills")
        assertEquals("feature/test", OperitGitHub.parse("https://github.com/o/r/tree/feature%2Ftest/skills").ref)
        assertEquals("main", parsed.ref)
        assertEquals("skills", parsed.subPath)
        assertEquals(null, OperitGitHub.parse("https://github.com/o/r").ref)
        rejected { OperitGitHub.parse("https://github.com.evil.test/o/r") }
        rejected { OperitGitHub.parse("https://github.com/o/r/tree/main/../outside") }
        rejected { OperitGitHub.parse("https://user:password@github.com/o/r") }
        rejected { OperitGitHub.parse("https://github.com/o/r/issues/1") }
    }

    @Test fun acceptsPublicDistributionSourcesButRejectsLocalAndCredentialUrls() {
        OperitMarketRepository.publicUrl("https://release-assets.githubusercontent.com/a?signature=fixture")
        OperitMarketRepository.publicUrl("https://plugins.example.org/asset.zip")
        OperitMarketRepository.publicUrl("https://[2606:4700:4700::1111]/asset.zip")
        for (url in listOf("http://github.com/o/r", "https://127.0.0.1/a", "https://localhost/a", "https://[fd00::1]/a", "https://[fc00::1]/a", "https://user:password@github.com/a")) {
            rejected { OperitMarketRepository.publicUrl(url) }
        }
        assertEquals("safe.zip", OperitMarketRepository.safeName("../../safe.zip"))
    }

    @Test fun rejectsMaliciousArchivePaths() {
        for (path in listOf("../SKILL.md", "/tmp/SKILL.md", "C:/SKILL.md", "a/../../b", "a\\b", "a//b", "a/./b")) {
            rejected { OperitMarketRepository.archivePath(path) }
        }
    }

    @Test fun scopesRepositoryArchiveToSpecifiedSubtree() = runBlocking {
        val source = archive("repo-main/skills/fixture/SKILL.md" to "fixture", "repo-main/outside/SKILL.md" to "outside")
        val output = ByteArrayOutputStream()
        OperitMarketRepository.scopeArchive(ByteArrayInputStream(source), output, "skills")
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use {
            assertEquals("fixture/SKILL.md", it.nextEntry.name)
            assertEquals("fixture", it.readBytes().toString(Charsets.UTF_8))
            assertEquals(null, it.nextEntry)
        }
    }

    @Test fun rejectsZipSlipMixedRootsAndAbsentSubtree() {
        for ((bytes, subtree) in listOf(
            archive("repo/../SKILL.md" to "fixture") to "skills",
            archive("repo/skills/SKILL.md" to "fixture", "other/SKILL.md" to "fixture") to "skills",
            archive("repo/else/SKILL.md" to "fixture") to "skills",
        )) {
            rejected { runBlocking { OperitMarketRepository.scopeArchive(ByteArrayInputStream(bytes), ByteArrayOutputStream(), subtree) } }
        }
    }

    @Test fun streamedCopyEnforcesLimitAndPropagatesCancellation() {
        rejected { runBlocking { OperitMarketRepository.copyBounded(ByteArrayInputStream(ByteArray(5)), ByteArrayOutputStream(), 4) } }
        try {
            runBlocking {
                coroutineContext.cancel()
                OperitMarketRepository.copyBounded(ByteArrayInputStream(ByteArray(5)), ByteArrayOutputStream(), 8)
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
    }

    private fun archive(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()

    private fun rejected(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        fail("Expected validation failure")
    }
}
