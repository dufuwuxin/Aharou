package com.aharou.feature.agent.domain.container

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.ArrayDeque

/** Local rootfs only. PRoot resolves guest absolute links against its root, not the Android host. */
internal object NativeArm64Rootfs {
    fun requireSupported(rootfs: File, shellPath: String? = null) {
        val release = resolveGuest(rootfs, "/etc/os-release")
        if (!release.isFile) throw IOException("Missing /etc/os-release; import a native ARM64 Linux image")
        val fields = release.readLines().mapNotNull { line ->
            val key = line.substringBefore('=', "").trim()
            if (key.isEmpty()) null else key to line.substringAfter('=').trim().trim('"', '\'')
        }.toMap()
        if (fields["ID"].isNullOrBlank()) throw IOException("Missing OS identity in /etc/os-release")
        if (fields["ID"].equals("alpine", ignoreCase = true) ||
            fields["ID_LIKE"].orEmpty().split(' ').any { it.equals("alpine", ignoreCase = true) } ||
            Files.exists(File(rootfs, "etc/alpine-release").toPath(), NOFOLLOW_LINKS)
        ) throw IOException("Alpine is no longer supported locally; import a native ARM64 Ubuntu image. Existing data is retained")
        requireElf(resolveGuest(rootfs, "/bin/sh"))
        if (!shellPath.isNullOrBlank() && shellPath != "/bin/sh") requireElf(resolveGuest(rootfs, shellPath))
    }

    fun requireUbuntu2404(rootfs: File) {
        requireSupported(rootfs)
        val fields = resolveGuest(rootfs, "/etc/os-release").readLines().associate {
            it.substringBefore('=').trim() to it.substringAfter('=', "").trim().trim('"', '\'')
        }
        if (fields["ID"] != "ubuntu" || fields["VERSION_ID"] != "24.04") {
            throw IOException("Builtin rootfs must be Ubuntu 24.04 LTS ARM64")
        }
    }

    private fun requireElf(shell: File) {
        if (!shell.isFile) throw IOException("Missing native shell: ${shell.name}; import a native ARM64 image")
        val header = ByteArray(20)
        var count = 0
        shell.inputStream().use { input ->
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read < 0) break
                count += read
            }
        }
        val magic = count == header.size && header[0] == 0x7f.toByte() &&
            header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        if (!magic || header[4].toInt() != 2 || header[5].toInt() != 1 || header[6].toInt() != 1 || machine != 183) {
            throw IOException("Local shell must be ELF64 AArch64 (e_machine=183); x86/ARM32 and ISA translation are not supported")
        }
    }

    internal fun resolveGuest(rootfs: File, guestPath: String): File {
        if (!guestPath.startsWith('/')) throw IOException("Guest path must be absolute: $guestPath")
        val root = rootfs.toPath().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(root)) throw IOException("Rootfs directory must not be a symlink")
        val pending = ArrayDeque<String>()
        guestPath.split('/').forEach { pending.addLast(it) }
        var current = root
        var hops = 0
        while (pending.isNotEmpty()) {
            when (val part = pending.removeFirst()) {
                "", "." -> Unit
                ".." -> {
                    if (current == root) throw IOException("Guest symlink escapes rootfs")
                    current = current.parent
                }
                else -> {
                    val next = current.resolve(part)
                    if (Files.isSymbolicLink(next)) {
                        if (++hops > 40) throw IOException("Guest symlink loop")
                        val target = Files.readSymbolicLink(next).toString()
                        if (target.startsWith('/')) current = root
                        target.split('/').asReversed().forEach { pending.addFirst(it) }
                    } else current = next
                }
            }
        }
        if (!current.startsWith(root)) throw IOException("Guest path escapes rootfs")
        return current.toFile()
    }
}
