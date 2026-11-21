// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.agent.domain.binary

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object ElfFixture {
    fun create(wide: Boolean = true, little: Boolean = true, sectionless: Boolean = false): ByteArray {
        val data = ByteArray(1280)
        val b = ByteBuffer.wrap(data).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        fun short(at: Int, value: Int) { b.putShort(at, value.toShort()) }
        fun int(at: Int, value: Int) { b.putInt(at, value) }
        fun word(at: Int, value: Long) { if (wide) b.putLong(at, value) else b.putInt(at, value.toInt()) }
        data[0] = 0x7f; data[1] = 69; data[2] = 76; data[3] = 70
        data[4] = if (wide) 2 else 1; data[5] = if (little) 1 else 2; data[6] = 1
        short(16, 3); short(18, if (wide) 183 else 40); int(20, 1); word(24, 0x1000)
        val ph = if (wide) 64 else 52
        val phSize = if (wide) 56 else 32
        word(if (wide) 32 else 28, ph.toLong()); word(if (wide) 40 else 32, if (sectionless) 0 else 640)
        short(if (wide) 52 else 40, ph); short(if (wide) 54 else 42, phSize); short(if (wide) 56 else 44, 2)
        short(if (wide) 58 else 46, if (wide) 64 else 40)
        short(if (wide) 60 else 48, if (sectionless) 0 else 7); short(if (wide) 62 else 50, if (sectionless) 0 else 1)
        fun program(at: Int, type: Int, offset: Int, size: Int) {
            int(at, type)
            if (wide) { int(at + 4, 5); word(at + 8, offset.toLong()); word(at + 16, 0x1000L + offset)
                word(at + 24, 0x1000L + offset); word(at + 32, size.toLong()); word(at + 40, size.toLong()); word(at + 48, 4096) }
            else { word(at + 4, offset.toLong()); word(at + 8, 0x1000L + offset); word(at + 12, 0x1000L + offset)
                word(at + 16, size.toLong()); word(at + 20, size.toLong()); int(at + 24, 5); word(at + 28, 4096) }
        }
        program(ph, 1, 0, data.size); program(ph + phSize, 2, 448, if (wide) 64 else 32)
        val names = "\u0000.shstrtab\u0000.dynstr\u0000.dynsym\u0000.rela.dyn\u0000.rodata\u0000.dynamic\u0000"
        names.toByteArray().copyInto(data, 192)
        val strings = "\u0000puts\u0000libc.so\u0000".toByteArray(); strings.copyInto(data, 288)
        "hello-somcp\u0000".toByteArray().copyInto(data, 416)
        fun section(index: Int, name: String, type: Int, offset: Int, size: Int, link: Int = 0, stride: Int = 0) {
            val at = 640 + index * if (wide) 64 else 40
            int(at, names.indexOf(name)); int(at + 4, type)
            word(at + 8, 2); word(at + if (wide) 16 else 12, 0x1000L + offset)
            word(at + if (wide) 24 else 16, offset.toLong()); word(at + if (wide) 32 else 20, size.toLong())
            int(at + if (wide) 40 else 24, link); word(at + if (wide) 48 else 32, 1)
            word(at + if (wide) 56 else 36, stride.toLong())
        }
        section(1, ".shstrtab", 3, 192, names.length)
        section(2, ".dynstr", 3, 288, strings.size)
        section(3, ".dynsym", 11, 320, if (wide) 48 else 32, 2, if (wide) 24 else 16)
        section(4, ".rela.dyn", 4, 384, if (wide) 24 else 12, 3, if (wide) 24 else 12)
        section(5, ".rodata", 1, 416, 12)
        section(6, ".dynamic", 6, 448, if (wide) 64 else 32, 2, if (wide) 16 else 8)
        val symbol = 320 + if (wide) 24 else 16
        int(symbol, 1); data[symbol + if (wide) 4 else 12] = 0x12
        word(384, 0x1234)
        if (wide) b.putLong(392, (1L shl 32) or 1026L) else int(388, (1 shl 8) or 22)
        val dynamicSize = if (wide) 16 else 8
        listOf(1L to 6L, 5L to 0x1120L, 10L to strings.size.toLong(), 0L to 0L).forEachIndexed { index, entry ->
            val at = 448 + index * dynamicSize
            word(at, entry.first); word(at + if (wide) 8 else 4, entry.second)
        }
        return data
    }
}
