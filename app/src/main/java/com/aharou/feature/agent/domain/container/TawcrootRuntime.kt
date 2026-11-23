package com.aharou.feature.agent.domain.container

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.IOException

internal object TawcrootRuntime {
    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libtawcroot.so")

    fun deviceSupported(api: Int, is64Bit: Boolean, abis: List<String>): Boolean =
        api >= 29 && is64Bit && "arm64-v8a" in abis

    fun available(context: Context): Boolean =
        deviceSupported(Build.VERSION.SDK_INT, Process.is64Bit(), Build.SUPPORTED_ABIS.toList()) &&
            binary(context).let { it.isFile && it.canExecute() }

    fun requireAvailable(context: Context) {
        check(available(context)) { "tawcroot requires Android 10+, a 64-bit ARM64 process and its bundled executable" }
    }

    fun availableForProfile(context: Context, profile: ContainerProfile, rootfs: File): Boolean {
        if (!available(context) || profile.mode != com.aharou.feature.settings.data.repository.ExecutionMode.LOCAL_PROOT) return false
        if (profile.isBuiltin && !rootfs.exists()) return true
        val marker = if (profile.isBuiltin) File(rootfs, ".installed") else File(rootfs, ".installed_custom")
        if (!marker.isFile) return false
        return runCatching { requireRootfs(rootfs) }.isSuccess
    }

    fun requireRootfs(rootfs: File) {
        NativeArm64Rootfs.requireSupported(rootfs)
        val release = NativeArm64Rootfs.resolveGuest(rootfs, "/etc/os-release").readLines().associate {
            it.substringBefore('=').trim() to it.substringAfter('=', "").trim().trim('"', '\'')
        }
        if (release["ID"] != "ubuntu") throw IOException("Experimental tawcroot currently requires an Ubuntu glibc rootfs")
        val loader = NativeArm64Rootfs.resolveGuest(rootfs, "/lib/ld-linux-aarch64.so.1")
        val header = ByteArray(20)
        var count = 0
        if (loader.isFile) loader.inputStream().use { input ->
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read < 0) break
                count += read
            }
        }
        if (count != 20 || header[0] != 0x7f.toByte() || header[1] != 69.toByte() ||
            header[2] != 76.toByte() || header[3] != 70.toByte() || header[4] != 2.toByte() ||
            header[5] != 1.toByte() || header[18] != 183.toByte() || header[19] != 0.toByte()
        ) throw IOException("Missing ELF64 AArch64 glibc loader /lib/ld-linux-aarch64.so.1")
    }

    fun prefix(binary: String, rootfs: String, bindings: List<String>): MutableList<String> =
        mutableListOf(binary, "-r", rootfs).apply {
            bindings.forEach { binding ->
                add("-b")
                add(if (':' in binding) binding else "$binding:$binding")
            }
            add("--")
        }

    fun command(command: String, hasWorkspace: Boolean): String =
        "cd ${if (hasWorkspace) "/root/workspace" else "/root"} || exit \$?; $command"

    fun environment(env: Map<String, String>): Map<String, String> =
        env.filterKeys { !it.startsWith("PROOT_") && it != "LD_LIBRARY_PATH" && it != "LD_PRELOAD" }

    fun environmentIfEnabled(argv: List<String>, env: Map<String, String>): Map<String, String> =
        if (argv.first().endsWith("/libtawcroot.so")) environment(env) else env

    fun ptyEnvironment(argv: List<String>, system: Map<String, String>, env: Map<String, String>): Array<String> =
        (system + env).let { environmentIfEnabled(argv, it) }.map { "${it.key}=${it.value}" }.toTypedArray()

    fun prepareProcessEnvironment(argv: List<String>, target: MutableMap<String, String>, env: Map<String, String>) {
        if (argv.first().endsWith("/libtawcroot.so")) {
            target.keys.removeAll { it.startsWith("PROOT_") || it == "LD_LIBRARY_PATH" || it == "LD_PRELOAD" }
        }
        target.putAll(env)
    }

    fun commandIfEnabled(argv: List<String>, command: String, hasWorkspace: Boolean): String =
        if (argv.first().endsWith("/libtawcroot.so")) command(command, hasWorkspace) else command

    fun wrapCommand(argv: List<String>, command: String, hasWorkspace: Boolean, env: Map<String, String>): String =
        ContainerDevelopmentEnvironment.wrap(commandIfEnabled(argv, command, hasWorkspace), environmentIfEnabled(argv, env))

    fun resultEnvironment(argv: List<String>, base: Map<String, String>, overrides: Map<String, String>): Map<String, String> =
        environmentIfEnabled(argv, base + overrides)

    fun enabled(profile: ContainerProfile): Boolean =
        profile.mode == com.aharou.feature.settings.data.repository.ExecutionMode.LOCAL_PROOT &&
            profile.runtimeBackend == ContainerRuntimeBackend.TAWCROOT

    fun executable(context: Context): String = binary(context).absolutePath

    fun prefixIfEnabled(
        context: Context,
        installer: ContainerInstaller,
        profile: ContainerProfile,
        argv: MutableList<String>,
        enabled: Boolean
    ): MutableList<String> {
        if (!enabled) {
            argv.addAll(profile.extraArgs)
            return argv
        }
        requireAvailable(context)
        val rootfs = installer.rootfsDirFor(profile)
        requireRootfs(rootfs)
        val bindings = argv.indices.filter { argv[it] == "-b" }.map { argv[it + 1] }
        return prefix(executable(context), rootfs.absolutePath, bindings)
    }
}
