package com.aharou.core.util

import java.io.File
import java.nio.file.Files

internal fun resolvePanelScriptFile(directory: File, name: String): File? = runCatching {
    if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) {
        return@runCatching null
    }
    val target = File(directory, name)
    if (Files.isSymbolicLink(target.toPath()) || target.canonicalFile.parentFile != directory.canonicalFile) {
        null
    } else {
        target
    }
}.getOrNull()
