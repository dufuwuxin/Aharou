package com.aharou.feature.localmodel.domain

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object LocalModelFiles {
    fun verify(file: File, spec: LocalModelSpec, checkActive: () -> Unit = {}) {
        require(spec.format == LocalModelFormat.GGUF && spec.backend == LocalModelBackend.LLAMA_CPP)
        if (!file.isFile || file.length() != spec.sizeBytes) throw IOException("Model size mismatch")
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || !magic.contentEquals(byteArrayOf(71, 71, 85, 70))) {
                throw IOException("Unsupported model format: GGUF required")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(magic)
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!actual.equals(spec.sha256, ignoreCase = true)) throw IOException("Model checksum mismatch")
        }
    }

    fun import(source: InputStream, target: File, spec: LocalModelSpec, checkActive: () -> Unit = {}) {
        target.parentFile?.mkdirs()
        val staging = File.createTempFile("model-import-", ".part", target.parentFile)
        try {
            staging.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var copied = 0L
                while (true) {
                    checkActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > spec.sizeBytes) throw IOException("Model exceeds catalog size")
                    output.write(buffer, 0, read)
                }
            }
            checkActive()
            verify(staging, spec, checkActive)
            checkActive()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
    }
}
