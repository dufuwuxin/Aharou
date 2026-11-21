// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.agent.domain.binary

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SomcpAnalyzerTest {
    @Test fun parses32And64BitFilesInBothByteOrders() {
        for (wide in listOf(false, true)) for (little in listOf(false, true)) {
            val report = SomcpAnalyzer().analyze(ElfFixture.create(wide, little))
            assertEquals(if (wide) 64 else 32, report["bits"]!!.jsonPrimitive.int)
            assertEquals(if (little) "little" else "big", report["endian"]!!.jsonPrimitive.content)
            assertEquals("libc.so", report["dependencies"]!!.jsonArray.single().jsonPrimitive.content)
            assertTrue(report["read_only"]!!.jsonPrimitive.boolean)
            assertFalse(report["deep_backends_available"]!!.jsonPrimitive.boolean)
        }
    }
    @Test fun sectionlessElfStillResolvesNeededLibrariesFromProgramHeaders() {
        val report = SomcpAnalyzer().analyze(ElfFixture.create(sectionless = true))
        assertEquals(0, report["section_count"]!!.jsonPrimitive.int)
        assertEquals("libc.so", report["dependencies"]!!.jsonArray.single().jsonPrimitive.content)
    }
    @Test fun relocationIndexesRetainTheNullSymbolAndResolveTheCorrectImport() {
        val bytes = ElfFixture.create()
        val imports = SomcpAnalyzer().analyze(bytes, "imports")["items"]!!.jsonArray
        assertEquals("puts", imports.single().jsonObject["name"]!!.jsonPrimitive.content)
        val relocations = SomcpAnalyzer().analyze(bytes, "relocations")["items"]!!.jsonArray
        assertEquals("puts", relocations.single().jsonObject["symbol"]!!.jsonPrimitive.content)
        assertTrue(SomcpAnalyzer().analyze(bytes, "exports")["items"]!!.jsonArray.isEmpty())
    }
    @Test fun paginationCoversAllSectionsAndFiltersWithoutLeakingWholeFileBytes() {
        val analyzer = SomcpAnalyzer(); val bytes = ElfFixture.create(); val all = mutableListOf<String>(); var offset = 0
        do {
            val report = analyzer.analyze(bytes, "sections", offset, 2)
            all += report["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            offset = (report["next_offset"] as? JsonPrimitive)?.intOrNull ?: -1
            assertFalse(report.containsKey("data"))
        } while (offset >= 0)
        assertEquals(7, all.size); assertEquals(7, all.distinct().size)
        assertEquals(4, analyzer.analyze(bytes, "sections", query = "dyn")["total"]!!.jsonPrimitive.int)
    }
    @Test fun changedContentInvalidatesTheSingleEntryCache() {
        val analyzer = SomcpAnalyzer(); val bytes = ElfFixture.create()
        val first = analyzer.analyze(bytes)["sha256"]
        bytes[420] = 'z'.code.toByte()
        assertNotEquals(first, analyzer.analyze(bytes)["sha256"])
    }
    @Test fun invalidMagicTruncationOffsetsStridesAndCountsAreRejected() {
        rejects(ByteArray(64))
        rejects(ElfFixture.create().copyOf(63))
        rejects(ElfFixture.create().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(40, Long.MAX_VALUE) })
        rejects(ElfFixture.create().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(60, 4097) })
        rejects(ElfFixture.create().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(58, 8) })
        rejects(ElfFixture.create().also { it[4] = 3 })
    }
    @Test fun excessiveStructuredRecordsAreRejectedBeforeAllocation() {
        val bytes = ElfFixture.create().copyOf(3_000_000)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(640 + 3 * 64 + 32, 100001L * 24)
        rejects(bytes)
    }
    @Test fun invalidPagesAndUnavailableDeepBackendsAreNotAdvertisedAsSuccess() {
        for (call in listOf<() -> Unit>(
            { SomcpAnalyzer().analyze(ElfFixture.create(), "decompile") },
            { SomcpAnalyzer().analyze(ElfFixture.create(), limit = 0) },
            { SomcpAnalyzer().analyze(ElfFixture.create(), offset = -1) })) {
            try { call(); fail("invalid request accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun readLimitIsEnforcedDuringStreamingWithoutTrustingStat() {
        assertArrayEquals(byteArrayOf(1, 2), BoundedBinaryReader.read(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
        try { BoundedBinaryReader.read(ByteArrayInputStream(ByteArray(20)), 10); fail("read exceeded limit") }
        catch (expected: IllegalArgumentException) { assertEquals("ELF_SIZE_LIMIT", expected.message) }
    }
    @Test fun deterministicMalformedInputsNeverEscapeAsUncheckedBufferAccess() {
        val random = Random(9041)
        repeat(300) {
            val bytes = ElfFixture.create()
            repeat(3) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            try { SomcpAnalyzer().analyze(bytes) } catch (_: IllegalArgumentException) { }
        }
    }
    private fun rejects(bytes: ByteArray) {
        try { SomcpAnalyzer().analyze(bytes); fail("invalid ELF accepted") } catch (_: IllegalArgumentException) { }
    }
}
