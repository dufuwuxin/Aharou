package com.aharou.feature.agent.domain.container

import com.aharou.feature.settings.data.repository.ExecutionMode
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Host log mapping shared by local PRoot entry points; no persisted profile fields. */
internal object ContainerLogMount {
    const val GUEST_LOG_DIR = "/root/log"

    fun environment(profile: ContainerProfile, projectPath: String?): Map<String, String> = mapOf(
        "AHAROU_LOG_DIR" to GUEST_LOG_DIR,
        "AHAROU_CONTAINER_ID" to profile.id,
        "AHAROU_WORKSPACE_ID" to (projectPath ?: "default")
    )

    fun appendDefaultBinding(
        argv: MutableList<String>,
        filesDir: File,
        profile: ContainerProfile,
        sharedDir: File = File("/storage/emulated/0/log"),
        prepare: (File) -> Boolean = ::prepareDirectory,
        warn: (String) -> Unit = {}
    ) {
        if (profile.mode != ExecutionMode.LOCAL_PROOT || profile.extraBindings.any(::targetsLogDirectory)) return
        val fallback = File(filesDir, "logs/build/${safeProfileId(profile.id)}")
        val source = when {
            available(sharedDir, prepare) -> sharedDir
            else -> {
                warn("Shared build log directory unavailable; using persistent app storage.")
                if (!available(fallback, prepare)) {
                    warn("Persistent build log directory unavailable; continuing without a log mount.")
                    return
                }
                fallback
            }
        }
        argv.addAll(listOf("-b", "${source.absolutePath}:$GUEST_LOG_DIR"))
    }

    private fun targetsLogDirectory(binding: String): Boolean {
        if (ContainerProfile.validateBinding(binding) != null) return false
        // PRoot accepts a trailing ! to disable guest-path symlink dereferencing.
        val target = binding.substringAfter(':').trim().removeSuffix("!")
            .split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/", prefix = "/")
        return target == GUEST_LOG_DIR
    }

    private fun safeProfileId(id: String): String =
        if (id.matches(Regex("[A-Za-z0-9_-]+"))) id else
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun available(directory: File, prepare: (File) -> Boolean): Boolean = try {
        prepare(directory)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun prepareDirectory(directory: File): Boolean {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
        if (!directory.canWrite()) return false
        // canWrite alone is unreliable on Android's shared-storage FUSE mounts.
        val probe = File.createTempFile(".aharou-log-", ".probe", directory)
        return probe.delete()
    }
}
