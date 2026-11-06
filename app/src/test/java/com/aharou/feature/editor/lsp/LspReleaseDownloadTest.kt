package com.aharou.feature.editor.lsp

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LspReleaseDownloadTest {
    private fun spec(asset: String = "server.tar.gz") = ServerSpec("test", "server", "test", InstallKind.RELEASE,
        releaseRepo = "official/server", releaseVersion = "v1", releaseAssetFor = { asset }, releaseEntryRelPath = "bin/server")
    @Test fun maliciousAssetAndEntryAreRejectedBeforeShell() {
        assertThrows(IllegalArgumentException::class.java) { LspReleaseDownload.script(spec("../bad.tar.gz")) }
        assertThrows(IllegalArgumentException::class.java) { LspReleaseDownload.script(spec("x\";touch p.tar.gz")) }
        assertThrows(IllegalArgumentException::class.java) { LspReleaseDownload.script(spec().copy(releaseEntryRelPath = "../server")) }
    }
    @Test fun officialDigestIsRequiredAndOldLauncherSurvivesFailures() {
        val root = Files.createTempDirectory("lsp-release-test").toFile()
        try {
            val home = root.resolve("home").apply { mkdirs() }
            val fixture = root.resolve("fixture").apply { mkdirs() }
            fixture.resolve("bin/server").apply { parentFile.mkdirs(); writeText("#!/bin/sh\necho verified\n"); setExecutable(true) }
            val archive = root.resolve("archive.tgz")
            assertEquals(0, ProcessBuilder("tar", "-czf", archive.path, "-C", fixture.path, "bin").start().waitFor())
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
            val fake = root.resolve("fake").apply { mkdirs() }
            fake.resolve("uname").apply { writeText("#!/bin/sh\necho aarch64\n"); setExecutable(true) }
            fake.resolve("curl").apply {
                writeText("#!/bin/sh\nwhile [ \"\$1\" != '-o' ]; do shift; done\ncase \"\$2\" in */release.json) cp \"\$TEST_ROOT/release.json\" \"\$2\";; *) cp \"\$TEST_ROOT/archive.tgz\" \"\$2\";; esac\n")
                setExecutable(true)
            }
            val launcher = home.resolve(".aharou/bin/server").apply { parentFile.mkdirs(); writeText("OLD-INSTALL") }
            val script = root.resolve("install.sh").apply { writeText(LspReleaseDownload.script(spec())!!) }
            fun run(metadata: String): Pair<Int, String> {
                root.resolve("release.json").writeText(metadata)
                val process = ProcessBuilder("sh", script.path).redirectErrorStream(true).apply {
                    environment()["HOME"] = home.path
                    environment()["PATH"] = fake.path + ":" + System.getenv("PATH")
                    environment()["TEST_ROOT"] = root.path
                }.start()
                val output = process.inputStream.bufferedReader().readText()
                return process.waitFor() to output
            }
            for (metadata in listOf("{\"assets\":[]}", "{\"assets\":[{\"name\":\"server.tar.gz\"}]}",
                "{\"assets\":[{\"name\":\"server.tar.gz\",\"digest\":\"sha256:${"0".repeat(64)}\"}]}")) {
                val (code, output) = run(metadata)
                assertNotEquals(output, 0, code)
                assertEquals("OLD-INSTALL", launcher.readText())
                assertTrue(home.resolve(".aharou/lsp").listFiles().orEmpty().isEmpty())
            }
            val (code, output) = run("{\"assets\":[{\"name\":\"server.tar.gz\",\"digest\":\"sha256:$digest\"}]}")
            assertEquals(output, 0, code)
            assertFalse(launcher.readText().contains("OLD-INSTALL"))
            val child = ProcessBuilder("sh", launcher.path).start()
            assertEquals("verified", child.inputStream.bufferedReader().readText().trim())
            assertEquals(0, child.waitFor())
        } finally { root.deleteRecursively() }
    }
}
