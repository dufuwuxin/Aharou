package com.aharou.feature.agent.domain.container

import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun collectBuiltinAssets(assets: AssetManager, directory: String): Map<String, ByteArray> {
    val files = linkedMapOf<String, ByteArray>()
    fun visit(path: String, relative: String) {
        val entries = assets.list(path) ?: throw IOException("Cannot list builtin assets: $path")
        if (entries.isEmpty()) {
            if (relative.isNotEmpty()) assets.open(path).use { files[relative] = it.readBytes() }
            return
        }
        entries.forEach { entry -> visit("$path/$entry", if (relative.isEmpty()) entry else "$relative/$entry") }
    }
    visit(directory, "")
    return files
}

internal class BuiltinAssetExtractor {
    private val extractedDocs = mutableSetOf<String>()

    @Synchronized
    fun extract(assets: AssetManager, configDir: File, directory: String): Int {
        require(directory in setOf("docs", "prompts", "agents", "teams", "skills"))
        val root = File(configDir, if (directory == "skills") "builtin-skills" else directory)
        check(!Files.isSymbolicLink(root.toPath())) { "Builtin asset directory is a symbolic link" }
        val key = root.canonicalPath
        if (directory == "docs" && root.isDirectory && key in extractedDocs) return 0
        val files = collectBuiltinAssets(assets, directory)
        if (directory == "docs" || directory == "prompts") check(files.isNotEmpty()) { "Missing builtin assets: $directory" }
        if (directory == "agents" || directory == "teams") return BuiltinSeedHistory(configDir, directory).extract(assets, files)
        check(root.isDirectory || root.mkdirs()) { "Cannot create builtin asset directory" }
        var written = 0
        for ((name, bytes) in files) {
            val target = resolve(root, name)
            if (target.isFile && target.readBytes().contentEquals(bytes)) continue
            check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Cannot create builtin asset parent" }
            val temporary = File.createTempFile(".aharou-asset-", ".tmp", target.parentFile)
            try {
                temporary.writeBytes(bytes)
                try {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                written++
            } finally { temporary.delete() }
        }
        if (directory == "docs") {
            // docs is the existing builtin-only namespace; never traverse links into user directories.
            root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter {
                it.isFile && !Files.isSymbolicLink(it.toPath()) && it.relativeTo(root).invariantSeparatorsPath !in files
            }.toList().forEach { stale ->
                resolve(root, stale.relativeTo(root).invariantSeparatorsPath)
                check(stale.delete()) { "Cannot remove obsolete builtin document" }
            }
            extractedDocs += key
        }
        return written
    }

    private fun resolve(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/') && relative.split('/').none { it == ".." || it.isEmpty() })
        var current = root
        for (part in relative.split('/')) {
            current = File(current, part)
            check(!Files.isSymbolicLink(current.toPath())) { "Builtin asset path is a symbolic link" }
        }
        check(current.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Builtin asset path escaped its directory" }
        return current
    }
}
