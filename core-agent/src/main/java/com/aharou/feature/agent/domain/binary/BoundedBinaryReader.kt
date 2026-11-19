// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.agent.domain.binary

import java.io.ByteArrayOutputStream
import java.io.InputStream

object BoundedBinaryReader {
    fun read(input: InputStream, limit: Int = ElfInputValidator.MAX_BYTES): ByteArray {
        require(limit in 1..ElfInputValidator.MAX_BYTES) { "ELF_SIZE_LIMIT" }
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("ELF analysis cancelled")
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "ELF_SIZE_LIMIT" }
            if (count == 0) continue
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
