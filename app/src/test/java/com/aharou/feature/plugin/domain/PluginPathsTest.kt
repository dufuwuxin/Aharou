package com.aharou.feature.plugin.domain

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginPathsTest {
    @Test fun validIdsStayInsidePluginRoot() {
        val root = Files.createTempDirectory("plugin-root-").toFile()
        try {
            for (id in listOf("example", "my-plugin_2.1", "A1")) {
                assertEquals(root.resolve(id).canonicalFile, PluginPaths.resolve(root, id))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidIdsCannotSelectAnotherDirectoryOrShellFragment() {
        val root = Files.createTempDirectory("plugin-root-").toFile()
        try {
            for (id in listOf("", ".", "..", "../other", "/tmp/other", "a/b", "a\\b", "a;id", "$(id)", "x\nnext")) {
                assertThrows(id, IllegalArgumentException::class.java) { PluginPaths.resolve(root, id) }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun existingLinksCannotRedirectPluginRoot() {
        val parent = Files.createTempDirectory("plugin-links-").toFile()
        try {
            val root = parent.resolve("plugins").apply { mkdirs() }
            val outside = parent.resolve("outside").apply { mkdirs() }
            Files.createSymbolicLink(root.resolve("redirect").toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) { PluginPaths.resolve(root, "redirect") }
            Files.createSymbolicLink(parent.resolve("linked-root").toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) { PluginPaths.resolve(parent.resolve("linked-root"), "valid") }
        } finally { parent.deleteRecursively() }
    }
}
