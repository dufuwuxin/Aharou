// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.agent.domain.binary

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Validates sizes as Long before the imported parser narrows them to Int. No target code is executed. */
internal object ElfInputValidator {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_RECORDS = 100_000L
    fun validate(bytes: ByteArray) {
        require(bytes.size in 52..MAX_BYTES) { "ELF_SIZE_LIMIT" }
        require(bytes[0] == 0x7f.toByte() && bytes[1] == 69.toByte() && bytes[2] == 76.toByte() && bytes[3] == 70.toByte()) { "NOT_ELF" }
        require(bytes[4].toInt() in 1..2 && bytes[5].toInt() in 1..2 && bytes[6] == 1.toByte()) { "ELF_IDENT_UNSUPPORTED" }
        val wide = bytes[4] == 2.toByte()
        val header = if (wide) 64 else 52
        require(bytes.size >= header) { "ELF_HEADER_TRUNCATED" }
        val buffer = ByteBuffer.wrap(bytes).order(if (bytes[5] == 1.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        fun u16(at: Int) = buffer.getShort(at).toInt() and 65535
        fun u32(at: Int) = buffer.getInt(at).toLong() and 0xffffffffL
        fun word(at: Int) = if (wide) buffer.getLong(at) else u32(at)
        fun range(offset: Long, size: Long) {
            require(offset >= 0 && size >= 0 && offset <= bytes.size && size <= bytes.size.toLong() - offset) { "ELF_RANGE_INVALID" }
        }
        fun table(offset: Long, count: Int, stride: Int, minimum: Int) {
            require(count <= 4096) { "ELF_TABLE_LIMIT" }
            if (count > 0) { require(stride >= minimum) { "ELF_ENTRY_SIZE_INVALID" }; range(offset, count.toLong() * stride) }
        }
        require(u32(20) == 1L && u16(if (wide) 52 else 40) >= header) { "ELF_HEADER_INVALID" }
        val phoff = word(if (wide) 32 else 28)
        val shoff = word(if (wide) 40 else 32)
        val phsize = u16(if (wide) 54 else 42)
        val phnum = u16(if (wide) 56 else 44)
        val shsize = u16(if (wide) 58 else 46)
        val shnum = u16(if (wide) 60 else 48)
        val names = u16(if (wide) 62 else 50)
        require(phnum != 65535 && names != 65535 && (shnum != 0 || shoff == 0L)) { "ELF_EXTENDED_NUMBERING_UNSUPPORTED" }
        table(phoff, phnum, phsize, if (wide) 56 else 32)
        table(shoff, shnum, shsize, if (wide) 64 else 40)
        require(names == 0 || names < shnum) { "ELF_SECTION_NAMES_INVALID" }
        repeat(phnum) { index ->
            val at = (phoff + index.toLong() * phsize).toInt()
            range(word(at + if (wide) 8 else 4), word(at + if (wide) 32 else 16))
        }
        var total = 0L
        var symbolTables = 0
        var dynamicSymbolTables = 0
        repeat(shnum) { index ->
            val at = (shoff + index.toLong() * shsize).toInt()
            val type = u32(at + 4)
            val offset = word(at + if (wide) 24 else 16)
            val size = word(at + if (wide) 32 else 20)
            require(size >= 0) { "ELF_RANGE_INVALID" }
            if (type != 8L) range(offset, size)
            val stride = word(at + if (wide) 56 else 36)
            val minimum = when (type) {
                2L, 11L -> if (wide) 24 else 16
                4L -> if (wide) 24 else 12
                9L, 6L -> if (wide) 16 else 8
                else -> 0
            }
            if (minimum != 0 && size > 0) {
                require(stride >= minimum && size % stride == 0L) { "ELF_ENTRY_SIZE_INVALID" }
                val count = size / stride
                total += count
                require(count <= MAX_RECORDS && total <= MAX_RECORDS) { "ELF_RECORD_LIMIT" }
            }
            if (type == 2L) symbolTables++
            if (type == 11L) dynamicSymbolTables++
            if (type in setOf(2L, 11L, 4L, 9L)) require(u32(at + if (wide) 40 else 24) < shnum) { "ELF_LINK_INVALID" }
        }
        // The imported parser exposes one symbol table per kind. Reject ambiguous multi-table files.
        require(symbolTables <= 1 && dynamicSymbolTables <= 1) { "ELF_MULTIPLE_SYMBOL_TABLES_UNSUPPORTED" }
    }
}
