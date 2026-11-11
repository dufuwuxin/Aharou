package com.aharou.feature.plugin.presentation

import com.aharou.feature.plugin.domain.market.PluginMarketEntry
import com.aharou.feature.plugin.domain.market.createMarketPackageTempFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginMarketProjectionTest {
    private val entries = listOf(
        PluginMarketEntry(id = "one", name = "Container Tools", description = "Run tasks", tags = listOf("shell")),
        PluginMarketEntry(id = "two", name = "Notebook", description = "Local NOTES", tags = listOf("writing")),
    )

    @Test
    fun searchIncludesNameDescriptionAndTagsWithoutChangingOrder() {
        assertEquals(entries, filterMarketPlugins(entries, "  "))
        assertEquals(listOf(entries[0]), filterMarketPlugins(entries, "container"))
        assertEquals(listOf(entries[1]), filterMarketPlugins(entries, " notes "))
        assertEquals(listOf(entries[0]), filterMarketPlugins(entries, "SHELL"))
        assertTrue(filterMarketPlugins(entries, "absent").isEmpty())
    }

    @Test
    fun versionProjectionDoesNotMistakeOlderOrUnknownForAnUpdate() {
        assertEquals(MarketVersionState.AVAILABLE, marketVersionState("", null))
        assertEquals(MarketVersionState.INSTALLED, marketVersionState("v1.2", "1.2.0"))
        assertEquals(MarketVersionState.UPDATE, marketVersionState("1.10.0", "1.9.9"))
        assertEquals(MarketVersionState.LOCAL_NEWER, marketVersionState("1.9", "1.10"))
        assertEquals(MarketVersionState.UNKNOWN, marketVersionState("", "1.0"))
        assertEquals(MarketVersionState.UNKNOWN, marketVersionState("1.0", ""))
        assertEquals(MarketVersionState.UNKNOWN, marketVersionState("1.0-rc1", "1.0"))
        assertEquals(MarketVersionState.UNKNOWN, marketVersionState("development", "1.0"))
        assertEquals(MarketVersionState.UPDATE, marketVersionState("999999999999999999999", "2"))
    }

    @Test
    fun missingAddressIsNotInstallable() {
        assertFalse(marketHasDownload(PluginMarketEntry()))
        assertFalse(marketHasDownload(PluginMarketEntry(downloadUrl = "   ")))
        assertTrue(marketHasDownload(PluginMarketEntry(downloadUrl = "https://example.com/package.txplugin")))
    }

    @Test
    fun displayNeverEchoesUserInfoQueryOrFragment() {
        assertEquals("https://example.com/package.txplugin", marketDownloadDisplay(
            "https://example-user:example-password@example.com/package.txplugin?signature=fixture#private"))
        assertNull(marketDownloadDisplay("not a url"))
        assertNull(marketDownloadDisplay("file:///private/package.txplugin"))
    }

    @Test
    fun remoteIdsCannotSelectCacheFileOrEscapeItsDirectory() {
        val cache = Files.createTempDirectory("market-fixture-").toFile()
        val files = mutableListOf<java.io.File>()
        try {
            val maliciousEntries = listOf("../../outside", "/tmp/outside", "..\\outside", "a/b", "", "duplicate", "duplicate")
                .map { PluginMarketEntry(id = it) }
            maliciousEntries.forEach { _ ->
                // The production factory deliberately accepts only cacheDir, never an entry or id.
                val file = createMarketPackageTempFile(cache)
                files.add(file)
                assertEquals(cache.canonicalFile, file.canonicalFile.parentFile)
                assertTrue(file.name.startsWith("plugin-market-"))
                assertTrue(file.name.endsWith(".txplugin"))
                assertTrue(file.isFile)
            }
            assertEquals(files.size, files.map { it.name }.toSet().size)
            assertNotEquals(files[5], files[6])
        } finally {
            files.forEach { it.delete() }
            cache.delete()
        }
    }
}
