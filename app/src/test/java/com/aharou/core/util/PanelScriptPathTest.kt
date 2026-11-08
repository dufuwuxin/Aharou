package com.aharou.core.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PanelScriptPathTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rejectsExternalNamesAndPreservesUnicode() {
        val dir = temp.newFolder("scripts")
        for (name in listOf("../outside.sh", "/outside.py", "a/b.js", "a\\b.sh", "", ".", "..", "bad\u0000.sh")) {
            assertNull(name, resolvePanelScriptFile(dir, name))
        }
        assertEquals(File(dir, "中文脚本.sh"), resolvePanelScriptFile(dir, "中文脚本.sh"))
    }

    @Test fun rejectsExistingAndDanglingEntryLinksWithoutTouchingTargets() {
        val dir = temp.newFolder("scripts")
        val outside = temp.newFile("outside.sh").apply { writeText("keep") }
        Files.createSymbolicLink(File(dir, "linked.sh").toPath(), outside.toPath())
        Files.createSymbolicLink(File(dir, "dangling.sh").toPath(), File(temp.root, "missing.sh").toPath())
        assertNull(resolvePanelScriptFile(dir, "linked.sh"))
        assertNull(resolvePanelScriptFile(dir, "dangling.sh"))
        assertEquals("keep", outside.readText())
    }

    @Test fun permitsTrustedDirectoryAliases() {
        val dir = temp.newFolder("scripts")
        val alias = File(temp.root, "alias")
        Files.createSymbolicLink(alias.toPath(), dir.toPath())
        assertEquals(File(alias, "safe.py"), resolvePanelScriptFile(alias, "safe.py"))
    }
}
