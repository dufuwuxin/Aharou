// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.agent.domain.binary

import com.soreverse.mcp.engine.ElfFile
import com.soreverse.mcp.engine.ElfParser
import com.soreverse.mcp.engine.SymbolInfo
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Local, read-only facade over SOMCP's pinned Kotlin ELF parser. Never loads or executes the input. */
class SomcpAnalyzer {
    private var cached: Pair<String, ElfFile>? = null

    @Synchronized
    fun analyze(bytes: ByteArray, action: String = "overview", offset: Int = 0, limit: Int = 50, query: String = ""): JsonObject {
        require(action in ACTIONS) { "ELF_ACTION_UNAVAILABLE" }
        require(offset >= 0 && limit in 1..100 && query.length <= 256) { "ELF_PAGE_INVALID" }
        ElfInputValidator.validate(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val elf = cached?.takeIf { it.first == hash }?.second ?: ElfParser(bytes).parse().also { cached = hash to it }
        val libraries = dependencies(elf)
        return buildJsonObject {
            put("engine", "Aharou-ELF")
            put("upstream_commit", UPSTREAM_COMMIT)
            put("read_only", true); put("sha256", hash); put("size_bytes", bytes.size)
            put("action", action)
            if (action == "overview") {
                put("bits", elf.bits); put("endian", elf.endian); put("architecture", elf.architecture)
                put("machine", elf.machine); put("machine_name", elf.machineName); put("type", elf.type); put("entry", hex(elf.entry))
                put("section_count", elf.sections.size); put("program_header_count", elf.programHeaders.size)
                put("symbol_count", (elf.symbols + elf.dynSymbols).count { it.name.isNotEmpty() })
                put("dependencies", libraries)
                put("strings_are_bounded", true)
                put("string_scan_policy", "Named .rodata/.strtab/.dynstr sections; 20000 candidates, 8 MiB shared text budget, 1024 displayed characters per string")
                put("deep_backends_available", false)
                put("available_actions", JsonArray(ACTIONS.map(::JsonPrimitive)))
            } else {
                val all = when (action) {
                    "sections" -> elf.sections.map { section -> buildJsonObject {
                        put("name", section.name); put("type", section.type); put("flags", hex(section.flags))
                        put("address", hex(section.addr)); put("offset", section.offset); put("size", section.size)
                    } }
                    "symbols", "imports", "exports" -> (if (action == "symbols") elf.symbols + elf.dynSymbols else elf.dynSymbols).filter {
                        it.name.isNotEmpty() && when (action) {
                            "imports" -> it.imported && it.bind in setOf("GLOBAL", "WEAK")
                            "exports" -> it.exported && it.visibility in setOf("DEFAULT", "PROTECTED")
                            else -> true
                        }
                    }.map(::symbol)
                    "dependencies" -> libraries.map { buildJsonObject { put("name", it.jsonPrimitive.content) } }
                    "strings" -> elf.strings.map { buildJsonObject {
                        put("value", it.value); put("offset", it.offset); put("section", it.section); put("original_bytes", it.length)
                    } }
                    "relocations" -> elf.relocations.map { buildJsonObject {
                        put("section", it.section); put("address", hex(it.offset)); put("type", it.type)
                        put("symbol", it.symbol); put("addend", it.addend)
                    } }
                    else -> emptyList()
                }.filter { query.isEmpty() || it.toString().contains(query, ignoreCase = true) }
                val page = mutableListOf<JsonElement>()
                var byteCount = 0
                for (element in all.drop(offset).take(limit)) {
                    val size = element.toString().toByteArray(Charsets.UTF_8).size
                    if (byteCount + size > 64 * 1024) break
                    page += element; byteCount += size
                }
                put("items", JsonArray(page)); put("total", all.size); put("offset", offset)
                put("next_offset", if (offset + page.size < all.size) JsonPrimitive(offset + page.size) else JsonNull)
                put("page_byte_limit", 64 * 1024)
                if (action == "strings") put("bounded_scan", true)
            }
        }
    }

    private fun dependencies(elf: ElfFile): JsonArray {
        val strtabVa = elf.dynamicEntries.firstOrNull { it.tag == 5L }?.value
        val stringSize = elf.dynamicEntries.firstOrNull { it.tag == 10L }?.value
        val load = strtabVa?.let { va -> elf.programHeaders.firstOrNull {
            it.type == 1L && va >= it.vaddr && va - it.vaddr < it.filesz
        } }
        val section = elf.sections.firstOrNull { it.name == ".dynstr" && it.type == 3L }
        val base = if (load != null && strtabVa != null) load.offset + (strtabVa - load.vaddr) else section?.offset
        val length = if (load != null && strtabVa != null) minOf(stringSize ?: (load.filesz - (strtabVa - load.vaddr)),
            load.filesz - (strtabVa - load.vaddr)) else section?.size
        val neededEntries = elf.dynamicEntries.filter { it.tag == 1L }
        if (neededEntries.isEmpty()) return JsonArray(emptyList())
        require(base != null && length != null && base >= 0 && length > 0 && base <= elf.data.size && length <= elf.data.size - base) {
            "ELF_DYNAMIC_STRING_TABLE_UNRESOLVED"
        }
        val names = neededEntries.map { needed ->
            require(needed.value >= 0 && needed.value < length) { "ELF_DEPENDENCY_RANGE_INVALID" }
            val start = base + needed.value
            val bound = minOf(base + length, start + 8192)
            var end = start.toInt()
            while (end.toLong() < bound && elf.data[end] != 0.toByte()) end++
            require(end.toLong() < bound) { "ELF_DEPENDENCY_NAME_INVALID" }
            elf.data.copyOfRange(start.toInt(), end).toString(Charsets.UTF_8)
        }.distinct()
        return JsonArray(names.map(::JsonPrimitive))
    }

    private fun symbol(value: SymbolInfo) = buildJsonObject {
        put("name", value.name); put("binding", value.bind); put("type", value.type); put("visibility", value.visibility)
        put("address", hex(value.value)); put("size", value.size); put("section_index", value.sectionIndex)
        put("imported", value.imported); put("exported", value.exported)
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        const val UPSTREAM_COMMIT = "f01f059bf4c903255464f1a073431c41ebd74eb5"
        val ACTIONS = listOf("overview", "sections", "symbols", "imports", "exports", "dependencies", "strings", "relocations")
        private fun hex(value: Long) = "0x" + value.toULong().toString(16)
    }
}
