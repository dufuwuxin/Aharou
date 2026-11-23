package com.aharou.feature.agent.domain.tool.custom

import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PracticalToolTemplatesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun draftsAreKeywordOnlyAndHaveExecutableParameterContracts() {
        assertEquals(setOf("git_status", "git_diff", "git_log", "find_files", "rtk_command", "auggie_command",
            "build_android", "build_rust", "build_python", "build_logs", "code_map", "code_check", "code_symbols", "code_callers", "codegraph_status", "codegraph_index"), PracticalToolTemplates.all.map { it.name }.toSet())
        PracticalToolTemplates.all.forEach { draft ->
            assertTrue(draft.isValidName)
            assertEquals(ToolInjectionMode.KEYWORD, draft.injectionMode)
            assertTrue(draft.keywords.isNotEmpty())
            assertTrue(draft.parameters.all { it.isValidName })
            assertTrue(CustomCommandTemplate.placeholders(draft.command).all { placeholder -> draft.parameters.any { it.name == placeholder } })
            val tool = CustomCommandTool(
                draft, io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk()
            )
            val schema = tool.toJsonSchema()
            assertTrue(ToolSchemaOverride.isCompatible(schema, Gson().toJson(schema)))
            assertEquals(draft.parameters.map { it.name }.toSet(), (schema["properties"] as Map<*, *>).keys)
        }
    }

    @Test fun customCommandResultsHonorContainerEnvRedactionSetting() {
        val settings = io.mockk.mockk<com.aharou.feature.settings.data.repository.ContainerSettingsRepository>()
        io.mockk.every { settings.redactEnvOutputEnabledSnapshot() } returns true
        val tool = CustomCommandTool(
            PracticalToolTemplates.all.first(), io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(), settings
        )
        assertEquals("TOKEN=[已隐藏]\n[退出码: 0]", tool.toModelOutput("TOKEN=private-token", 0, mapOf("TOKEN" to "private-token")))
        io.mockk.every { settings.redactEnvOutputEnabledSnapshot() } returns false
        assertEquals("TOKEN=private-token\n[退出码: 0]", tool.toModelOutput("TOKEN=private-token", 0, mapOf("TOKEN" to "private-token")))
    }

    @Test fun untrustedValuesStayInQuotedArgvNotShellSource() {
        val payload = "x'; touch /tmp/template-injection; \$(id) `id` {{path}}"
        PracticalToolTemplates.all.forEach { draft ->
            val command = CustomCommandTemplate.renderForExecution(draft.command, draft.parameters, draft.parameters.associate { it.name to payload })
            assertTrue(command is CustomCommandRender.Success)
            val rendered = (command as CustomCommandRender.Success).command
            if (CustomCommandTemplate.placeholders(draft.command).isNotEmpty()) {
                assertTrue(rendered.startsWith("sh -c "))
                assertTrue(rendered.contains(" custom-tool "))
                assertFalse(rendered.contains("custom-tool $payload"))
                assertTrue(rendered.contains("'\"'\"'"))
            } else {
                assertEquals(draft.command, rendered)
                assertFalse(rendered.contains(payload))
            }
        }
    }

    @Test fun buildTemplatesUseFixedCommandsAndOneAndroidTaskParameter() {
        val builds = PracticalToolTemplates.all.filter { it.name.startsWith("build_") }.associateBy { it.name }
        mapOf("build_android" to "android {{task}}", "build_rust" to "rust",
            "build_python" to "python", "build_logs" to "logs").forEach { (name, action) ->
            val draft = builds.getValue(name)
            assertEquals("aharou-build $action", draft.command)
            assertEquals(600, draft.timeoutSeconds)
            if (name == "build_android") {
                assertEquals(listOf("task"), draft.parameters.map { it.name })
                assertTrue(draft.parameters.single().required)
            } else {
                assertTrue(draft.parameters.isEmpty())
            }
        }
    }

    @Test fun auggieReceivesLiteralStdinAndWorkspaceArgv() {
        val payloads = listOf(
            "--help --dangerously-skip-permissions",
            "@private-file",
            "x'; printf injected > injected; \$(printf injected > injected) `printf injected > injected` {{path}}\nsecond line"
        )
        payloads.forEach { assertAuggieProcess(it, 0) }
    }

    @Test fun auggieNonzeroExitIsPreserved() {
        assertAuggieProcess("Task with 'quotes' and a newline\n", 37)
    }

    private fun assertAuggieProcess(instruction: String, exitCode: Int) {
        // Host JVM fixture only; never invoke an installed CLI or run this on Android.
        assumeTrue(File("/bin/sh").canExecute() && File("/bin/cat").canExecute())
        assumeTrue(!System.getProperty("java.vm.name", "").contains("Dalvik", ignoreCase = true))
        val directory = temp.newFolder()
        val fake = File(directory, "auggie").apply {
            writeText("#!/bin/sh\nprintf '%s\\000' \"\$@\"\n/bin/cat\nprintf '%s' 'fake diagnostic' >&2\nexit $exitCode\n")
        }
        assertTrue(fake.setExecutable(true))
        val draft = PracticalToolTemplates.all.single { it.name == "auggie_command" }
        assertEquals(600, draft.timeoutSeconds)
        assertEquals(setOf("path", "instruction"), draft.parameters.filter { it.required }.map { it.name }.toSet())
        val workspace = "./project 'quoted' \$(printf injected > injected)\nnext"
        val rendered = CustomCommandTemplate.renderForExecution(
            draft.command, draft.parameters, mapOf("path" to workspace, "instruction" to instruction)
        )
        assertTrue(rendered is CustomCommandRender.Success)
        val process = ProcessBuilder("/bin/sh", "-c", (rendered as CustomCommandRender.Success).command)
            .directory(directory).apply {
                environment()["PATH"] = "${directory.absolutePath}:/usr/bin:/bin"
            }.start()
        try {
            process.outputStream.close()
            assertTrue("Fake Auggie timed out", process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(exitCode, process.exitValue())
            assertEquals("fake diagnostic", process.errorStream.bufferedReader().readText())
            val expectedArgs = listOf("--print", "--workspace-root", workspace, "Follow the task supplied on standard input.")
            assertEquals(expectedArgs.joinToString("\u0000", postfix = "\u0000") + instruction + "\n",
                process.inputStream.bufferedReader().readText())
            assertFalse(File(directory, "injected").exists())
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun rtkNeverInstallsOrSilentlyFallsBack() {
        val rtk = PracticalToolTemplates.all.single { it.name == "rtk_command" }
        assertTrue(rtk.command.contains("rtk git status"))
        assertFalse(rtk.command.contains("||"))
        assertFalse(rtk.command.contains("install"))
        assertFalse(rtk.command.contains("eval"))
    }
}
