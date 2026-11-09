package com.aharou.feature.agent.domain.container

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE

/** A failed extraction/probe/copy never deletes the old builtin or its homes. */
internal class BuiltinContainerMigration(private val filesDir: File) {
    val destination = File(filesDir, "rootfs_ubuntu")
    val legacy = File(filesDir, "rootfs")
    val stage = File(filesDir, "rootfs_ubuntu.stage")
    private val previous = File(filesDir, "rootfs_ubuntu.previous")
    private val managedBindings = setOf("workspace", "shared", ".aharou", ".aicode", "log")
    private val managedHomes = mutableListOf<Pair<Path, String>>()

    fun prepareStage(): File {
        if (!exists(destination) && exists(previous)) move(previous, destination)
        // An interrupted commit keeps previous as another home source until the next successful commit.
        removeTree(stage)
        if (!stage.mkdirs()) throw IOException("Cannot create Ubuntu staging directory")
        return stage
    }

    fun commit(version: String, validate: (File) -> Unit) {
        validate(stage)
        managedHomes.clear()
        for (old in listOf(legacy, previous, destination)) {
            if (!exists(old)) continue
            if (Files.isSymbolicLink(old.toPath())) throw IOException("Old rootfs directory is a symlink; data retained")
            for (name in listOf("root", "home")) {
                val source = File(old, name)
                if (exists(source)) copyHome(source.toPath(), File(stage, name).toPath(), name == "root")
            }
        }
        if (managedHomes.isNotEmpty()) {
            var archive = File(stage, "root/.container-migration").toPath()
            var suffix = 0
            while (Files.exists(archive, NOFOLLOW_LINKS)) {
                archive = File(stage, "root/.container-migration-${++suffix}").toPath()
            }
            safeDirectories(archive)
            for ((source, origin) in managedHomes) copyHome(source, archive.resolve(origin).resolve(source.fileName))
        }
        validate(stage)
        File(stage, ".installed").writeText(version)
        // Do not replace a recovery backup when both trees exist after an interrupted cleanup.
        if (exists(previous) && exists(destination)) throw IOException("Ubuntu recovery backup needs recovery; all trees retained")
        if (exists(destination)) move(destination, previous)
        try {
            move(stage, destination)
        } catch (error: Exception) {
            if (!exists(destination) && exists(previous)) move(previous, destination)
            throw error
        }
        finishCleanup()
    }

    /** Only called after a current marker and a successful native Ubuntu validation. */
    fun finishCleanup() {
        NativeArm64Rootfs.requireUbuntu2404(destination)
        if (!File(destination, ".installed").isFile) throw IOException("Ubuntu commit marker missing")
        removeTree(previous)
        removeTree(legacy)
    }

    private fun copyHome(source: Path, target: Path, rootHome: Boolean = false) {
        safeDirectories(target.parent)
        if (Files.isSymbolicLink(source)) {
            removeTree(target.toFile())
            Files.createSymbolicLink(target, Files.readSymbolicLink(source))
        } else if (Files.isDirectory(source, NOFOLLOW_LINKS)) {
            if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isDirectory(target, NOFOLLOW_LINKS)) removeTree(target.toFile())
            Files.createDirectories(target)
            val sourcePermissions = Files.getPosixFilePermissions(source, NOFOLLOW_LINKS)
            val readPermissions = setOf(OWNER_READ, OWNER_EXECUTE)
            val temporaryRead = !sourcePermissions.containsAll(readPermissions)
            if (temporaryRead) Files.setPosixFilePermissions(source, sourcePermissions + readPermissions)
            try {
                Files.newDirectoryStream(source).use { children ->
                    for (child in children) {
                        if (rootHome && child.fileName.toString() in managedBindings) {
                            managedHomes += child to source.parent.fileName.toString()
                        } else copyHome(child, target.resolve(child.fileName))
                    }
                }
            } finally {
                if (temporaryRead) Files.setPosixFilePermissions(source, sourcePermissions)
            }
            // Android opens the target to set its timestamp, so restore restrictive permissions last.
            Files.setLastModifiedTime(target, Files.getLastModifiedTime(source, NOFOLLOW_LINKS))
            Files.setPosixFilePermissions(target, sourcePermissions)
        } else if (Files.isRegularFile(source, NOFOLLOW_LINKS)) {
            if (Files.isDirectory(target, NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) removeTree(target.toFile())
            Files.copy(source, target, REPLACE_EXISTING, COPY_ATTRIBUTES, NOFOLLOW_LINKS)
            if (Files.size(source) != Files.size(target)) throw IOException("Home copy incomplete; original data retained")
        } else throw IOException("Unsupported home entry: $source; original data retained")
    }

    private fun safeDirectories(directory: Path) {
        val root = stage.toPath().toAbsolutePath().normalize()
        val target = directory.toAbsolutePath().normalize()
        if (!target.startsWith(root) || Files.isSymbolicLink(root)) throw IOException("Migration target escapes staging directory")
        var current = root
        for (part in root.relativize(target)) {
            current = current.resolve(part)
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, NOFOLLOW_LINKS)) throw IOException("Migration ancestor is not a real directory; originals retained")
            } else Files.createDirectory(current)
        }
    }

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("Cannot move ${from.name} to ${to.name}; original data retained")
    }

    private fun exists(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)

    internal fun removeTree(directory: File) {
        if (!exists(directory)) return
        val adjusted = mutableMapOf<Path, Set<PosixFilePermission>>()
        fun remove(path: Path) {
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
                val required = setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)
                if (!permissions.containsAll(required)) {
                    adjusted[path] = permissions
                    Files.setPosixFilePermissions(path, permissions + required)
                }
                Files.newDirectoryStream(path).use { children ->
                    for (child in children) remove(child)
                }
            }
            Files.delete(path)
            adjusted.remove(path)
        }
        try {
            remove(directory.toPath())
        } catch (error: Exception) {
            restoreAfterDeleteFailure(adjusted, error)
        }
    }

    internal fun restoreAfterDeleteFailure(adjusted: Map<Path, Set<PosixFilePermission>>, error: Exception): Nothing {
        for ((dir, permissions) in adjusted.entries.toList().asReversed()) {
            if (!Files.exists(dir, NOFOLLOW_LINKS)) continue
            try {
                Files.setPosixFilePermissions(dir, permissions)
            } catch (restoreError: Exception) {
                error.addSuppressed(restoreError)
            }
        }
        throw error
    }
}
