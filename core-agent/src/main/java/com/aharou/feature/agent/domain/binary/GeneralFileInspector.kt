package com.aharou.feature.agent.domain.binary

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Generic bounded metadata. Archive analysis reads the central directory; never extracts or executes entries. */
object GeneralFileInspector {
    val ACTIONS = listOf("overview", "text", "archive")
    fun inspect(bytes: ByteArray, name: String = "", action: String = "overview", offset: Int = 0,
        limit: Int = 50, query: String = ""): JsonObject {
        require(bytes.size <= SomcpAnalyzer.MAX_BYTES) { "FILE_ANALYSIS_SIZE_LIMIT" }
        require(action in ACTIONS && offset >= 0 && limit in 1..100 && query.length <= 256) { "FILE_ANALYSIS_ARGS_INVALID" }
        val format = detect(bytes, name)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return buildJsonObject {
            put("engine", "Aharou-file-analysis"); put("format", format); put("action", action)
            put("size_bytes", bytes.size); put("sha256", hash); put("read_only", true)
            put("supported_analysis", when {
                format == "ELF" -> "ELF metadata; use elf_analyze for sections, symbols, dependencies and relocations"
                format.startsWith("ZIP") -> "Bounded central directory metadata; entries are never decompressed or extracted"
                format.startsWith("Text/") -> "Bounded UTF text preview with character offsets"
                else -> "Format identification, byte size, SHA-256 and bounded header bytes; deep parsing is unavailable"
            })
            put("header_hex", bytes.take(32).joinToString(" ") { "%02x".format(it.toInt() and 255) })
            when (action) {
                "text" -> {
                    require(format.startsWith("Text/")) { "FILE_TEXT_UNAVAILABLE" }
                    val text = decode(bytes, format)
                    val start = offset.coerceAtMost(text.length)
                    var end = minOf(text.length.toLong(), start.toLong() + minOf(limit * 256, 16_384)).toInt()
                    if (end < text.length && end > start && text[end - 1].isHighSurrogate()) end--
                    put("text", text.substring(start, end)); put("offset", start); put("total", text.length)
                    put("next_offset", if (end < text.length) JsonPrimitive(end) else JsonNull)
                }
                "archive" -> {
                    require(format.startsWith("ZIP")) { "FILE_ARCHIVE_UNAVAILABLE" }
                    put("archive", archive(bytes, offset, limit, query))
                }
                else -> if (format.startsWith("Text/")) {
                    val text = decode(bytes, format)
                    put("text_preview", text.take(4096)); put("preview_truncated", text.length > 4096)
                }
            }
        }
    }

    fun detect(bytes: ByteArray, name: String = ""): String {
        fun starts(vararg value: Int) = bytes.size >= value.size && value.indices.all { bytes[it].toInt() and 255 == value[it] }
        fun ascii(at: Int, value: String) = bytes.size >= at + value.length && value.indices.all { bytes[at + it].toInt() == value[it].code }
        return when {
            starts(0x7f, 69, 76, 70) -> "ELF"
            starts(0x89, 80, 78, 71, 13, 10, 26, 10) -> "Image/PNG"
            starts(0xff, 0xd8, 0xff) -> "Image/JPEG"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "Image/GIF"
            ascii(0, "BM") -> "Image/BMP"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "Image/WebP"
            ascii(0, "%PDF-") -> "PDF"
            starts(80, 75, 3, 4) || starts(80, 75, 5, 6) || starts(80, 75, 7, 8) -> when (name.substringAfterLast('.', "").lowercase()) {
                "apk" -> "ZIP/APK-candidate"; "jar" -> "ZIP/JAR-candidate"
                "docx", "xlsx", "pptx" -> "ZIP/Office-candidate"; else -> "ZIP"
            }
            ascii(0, "dex\n") && bytes.size >= 8 && bytes[7] == 0.toByte() -> "DEX"
            ascii(0, "SQLite format 3\u0000") -> "SQLite"
            starts(0xca, 0xfe, 0xba, 0xbe) -> if (bytes.size >= 10 && u16be(bytes, 6) in 45..100) "Java/class" else "Mach-O/FAT"
            starts(0xce, 0xfa, 0xed, 0xfe) || starts(0xcf, 0xfa, 0xed, 0xfe) || starts(0xfe, 0xed, 0xfa, 0xce) || starts(0xfe, 0xed, 0xfa, 0xcf) -> "Mach-O"
            ascii(0, "MZ") -> "PE/DOS-candidate"
            starts(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1) -> "OLE/Compound"
            starts(0xff, 0xfe) -> "Text/UTF-16LE"
            starts(0xfe, 0xff) -> "Text/UTF-16BE"
            utf8(bytes) != null -> "Text/UTF-8"
            else -> "Binary/Unknown"
        }
    }

    private fun archive(bytes: ByteArray, offset: Int, limit: Int, query: String): JsonObject {
        fun u16(at: Int): Int { require(at >= 0 && at <= bytes.size - 2) { "ZIP_TRUNCATED" }; return (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) }
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        fun u64(at: Int): Long { val high = u32(at + 4); require(high < 0x80000000L) { "ZIP_RANGE_INVALID" }; return u32(at) or (high shl 32) }
        var eocd = bytes.size - 22
        val lower = maxOf(0, bytes.size - 65_557)
        while (eocd >= lower && u32(eocd) != 0x06054b50L) eocd--
        require(eocd >= lower && eocd >= 0) { "ZIP_DIRECTORY_MISSING" }
        require(u16(eocd + 4) == 0 && u16(eocd + 6) == 0 && eocd.toLong() + 22 + u16(eocd + 20) <= bytes.size) { "ZIP_MULTIPART_OR_TRUNCATED" }
        var entries = u16(eocd + 10).toLong(); var size = u32(eocd + 12); var at = u32(eocd + 16)
        if (entries == 65535L || size == 0xffffffffL || at == 0xffffffffL) {
            val locator = eocd - 20
            require(locator >= 0 && u32(locator) == 0x07064b50L && u32(locator + 4) == 0L && u32(locator + 16) == 1L) { "ZIP64_LOCATOR_INVALID" }
            val zip64 = u64(locator + 8)
            require(zip64 >= 0 && zip64 <= bytes.size - 56L) { "ZIP64_RANGE_INVALID" }
            val z = zip64.toInt()
            require(u32(z) == 0x06064b50L && u32(z + 16) == 0L && u32(z + 20) == 0L) { "ZIP64_HEADER_INVALID" }
            entries = u64(z + 32); size = u64(z + 40); at = u64(z + 48)
        }
        require(entries <= 100_000 && at >= 0 && size >= 0 && at <= bytes.size && size <= bytes.size - at && at + size <= eocd) { "ZIP_DIRECTORY_RANGE_INVALID" }
        val directoryEnd = at + size
        val result = mutableListOf<JsonElement>(); var matching = 0; var emittedBytes = 0
        repeat(entries.toInt()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("file analysis cancelled")
            require(at <= directoryEnd - 46) { "ZIP_DIRECTORY_TRUNCATED" }
            val p = at.toInt(); require(u32(p) == 0x02014b50L) { "ZIP_ENTRY_INVALID" }
            val nameSize = u16(p + 28); val extra = u16(p + 30); val comment = u16(p + 32)
            val next = at + 46 + nameSize + extra + comment
            require(nameSize <= 8192 && next <= directoryEnd) { "ZIP_ENTRY_RANGE_INVALID" }
            val charset = if (u16(p + 8) and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1
            val name = bytes.copyOfRange(p + 46, p + 46 + nameSize).toString(charset)
            if (query.isEmpty() || name.contains(query, ignoreCase = true)) {
                if (matching >= offset && result.size < limit && emittedBytes + nameSize < 48 * 1024) {
                    result += buildJsonObject {
                        put("name", name); put("directory", name.endsWith('/')); put("compression_method", u16(p + 10))
                        put("compressed_bytes", if (u32(p + 20) == 0xffffffffL) JsonNull else JsonPrimitive(u32(p + 20)))
                        put("uncompressed_bytes", if (u32(p + 24) == 0xffffffffL) JsonNull else JsonPrimitive(u32(p + 24)))
                    }; emittedBytes += nameSize
                }
                matching++
            }
            at = next
        }
        return buildJsonObject {
            put("items", JsonArray(result)); put("entries_in_archive", entries); put("matching_entries", matching); put("offset", offset)
            put("next_offset", if (offset.toLong() + result.size < matching) JsonPrimitive(offset + result.size) else JsonNull)
            put("method", "central directory only; no decompression or extraction")
        }
    }

    private fun u16be(b: ByteArray, at: Int) = ((b[at].toInt() and 255) shl 8) or (b[at + 1].toInt() and 255)
    private fun utf8(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()?.takeIf { value ->
            value.count { it.code < 32 && it !in "\t\r\n" } * 20L <= value.length.coerceAtLeast(1)
        }
    }
    private fun decode(bytes: ByteArray, format: String): String = when (format) {
        "Text/UTF-16LE" -> bytes.toString(Charsets.UTF_16LE).removePrefix("\uFEFF")
        "Text/UTF-16BE" -> bytes.toString(Charsets.UTF_16BE).removePrefix("\uFEFF")
        else -> utf8(bytes).orEmpty().removePrefix("\uFEFF")
    }
}
