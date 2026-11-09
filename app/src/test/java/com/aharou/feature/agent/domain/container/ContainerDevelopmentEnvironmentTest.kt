package com.aharou.feature.agent.domain.container

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContainerDevelopmentEnvironmentTest {
    @get:Rule val temp = TemporaryFolder()

    private fun execute(script: String, args: List<String> = emptyList(), input: String = ""): String {
        val process = ProcessBuilder(listOf("/bin/sh", "-c", script) + args).apply {
            environment().clear()
            environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to temp.root.path))
        }.start()
        process.outputStream.bufferedWriter().use { it.write(input) }
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        assertEquals(error, 0, process.waitFor())
        assertEquals("", error)
        return output
    }

    @Test fun missingProfileKeepsExistingCommandEnvironment() {
        val script = ContainerDevelopmentEnvironment.wrap("printf '%s' \"\$PATH\"", profile = File(temp.root, "missing").path)
        assertEquals("/usr/bin:/bin", execute(script))
    }

    @Test fun installedProfileIsSilentAndCustomEnvironmentStillWins() {
        val profile = temp.newFile("profile ' with spaces.sh").apply {
            writeText("echo noise; echo error >&2; export ANDROID_HOME=/installed/sdk JAVA_HOME=/installed/jdk PATH=/installed/bin:\$PATH\n")
        }
        val print = "printf '%s|%s|%s' \"\$ANDROID_HOME\" \"\$JAVA_HOME\" \"\$PATH\""
        assertEquals("/installed/sdk|/installed/jdk|/installed/bin:/usr/bin:/bin",
            execute(ContainerDevelopmentEnvironment.wrap(print, profile = profile.path)))
        val custom = mapOf("PATH" to "/user/bin", "JAVA_HOME" to "/user's jdk\nsecond line")
        assertEquals("/installed/sdk|/user's jdk\nsecond line|/user/bin",
            execute(ContainerDevelopmentEnvironment.wrap(print, custom, profile.path)))
    }

    @Test fun stdioPreservesStdinAndEveryArgIncludingSpacesQuotesAndNewlines() {
        val profile = temp.newFile().apply { writeText("export JAVA_HOME=/installed/jdk\n") }
        val args = listOf("space arg", "single'quote", "line\nbreak", "")
        val wrapped = ContainerDevelopmentEnvironment.wrap("exec \"\$0\" \"\$@\"", profile = profile.path)
        val output = execute(wrapped, listOf("/bin/sh", "-c", "printf '%s\\000' \"\$@\"; cat", "fixture") + args, "stdin\n")
        assertEquals(args.joinToString("\u0000", postfix = "\u0000") + "stdin\n", output)
    }

    @Test fun consumerSourceWiringUsesBothWrappersAndDoesNotLogThePayload() {
        var root = File(System.getProperty("user.dir"))
        while (!File(root, "app/src/main").isDirectory && root.parentFile != null) root = root.parentFile
        val source = File(root, "app/src/main/java/com/aharou/feature/agent/domain/container/LinuxContainerEngine.kt").readText()
        assertEquals(2, Regex("ContainerDevelopmentEnvironment\\.wrap\\(").findAll(source).count())
        assertTrue(source.contains("envVarRepository.asMap() + profile.env + extraEnv"))
        val terminal = File(root, "app/src/main/java/com/aharou/feature/terminal/domain/TerminalSessionManager.kt").readText()
        assertFalse(terminal.contains("export ENV=/etc/profile"))
        assertFalse(terminal.contains("invocation.argv.joinToString"))
    }
}
