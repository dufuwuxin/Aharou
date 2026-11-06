package com.aharou.feature.plugin.domain

import java.io.File
import java.nio.file.Files

internal object PluginPaths {
    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun resolve(root: File, id: String): File {
        require(idPattern.matches(id)) { "Invalid plugin id" }
        check(!Files.isSymbolicLink(root.toPath())) { "Plugin root is a symbolic link" }
        val target = File(root, id)
        check(!Files.isSymbolicLink(target.toPath())) { "Plugin directory is a symbolic link" }
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        check(canonicalTarget.parentFile == canonicalRoot) { "Plugin path escaped its root" }
        return canonicalTarget
    }
}
