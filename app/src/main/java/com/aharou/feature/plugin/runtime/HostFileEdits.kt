package com.aharou.feature.plugin.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object HostFileEdits {
    data class Snapshot(val bytes: ByteArray) {
        val sha256 = digest(bytes)
        val text: String get() = bytes.toString(Charsets.UTF_8)
    }

    fun read(file: File): Snapshot {
        require(file.isFile) { "Path is not a file" }
        val bytes = file.inputStream().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 4_000_000) { "Use the host SDK for files above 4 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return Snapshot(bytes)
    }

    fun write(file: File, content: String, expected: String?,
        writeBytes: (File, ByteArray) -> Unit = { target, bytes -> target.outputStream().use { it.write(bytes); it.fd.sync() } },
    ): Snapshot = synchronized(file.canonicalPath.intern()) {
        val destination = file.canonicalFile
        fun unchanged() = if (destination.exists()) expected != null && digest(destination.readBytes()) == expected else expected == null
        check(unchanged()) { "STALE_FILE: read the current file before overwriting" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val parent = requireNotNull(destination.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Parent directory could not be created" }
        val temporary = Files.createTempFile(parent.toPath(), ".aharou-edit-", ".tmp")
        try {
            writeBytes(temporary.toFile(), bytes)
            if (destination.exists()) Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(destination.toPath()))
            check(unchanged()) { "STALE_FILE: file changed while preparing the edit" }
            Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Snapshot(bytes)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
