package com.aharou.feature.agent.domain.tool.custom

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticalToolTemplatesTest {
    @Test fun draftsAreKeywordOnlyAndHaveExecutableParameterContracts() {
        assertEquals(setOf("git_status", "git_diff", "git_log", "find_files", "rtk_command",
            "build_android", "build_rust", "build_python", "build_logs"), PracticalToolTemplates.all.map { it.name }.toSet())
        PracticalToolTemplates.all.forEach { draft ->
            assertTrue(draft.isValidName)
            assertEquals(ToolInjectionMode.KEYWORD, draft.injectionMode)
            assertTrue(draft.keywords.isNotEmpty())
            assertTrue(draft.parameters.all { it.isValidName })
            assertTrue(CustomCommandTemplate.placeholders(draft.command).all { placeholder -> draft.parameters.any { it.name == placeholder } })
            val tool = CustomCommandTool(draft, io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk())
            val schema = tool.toJsonSchema()
            assertTrue(ToolSchemaOverride.isCompatible(schema, Gson().toJson(schema)))
            assertEquals(draft.parameters.map { it.name }.toSet(), (schema["properties"] as Map<*, *>).keys)
        }
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

    @Test fun rtkNeverInstallsOrSilentlyFallsBack() {
        val rtk = PracticalToolTemplates.all.single { it.name == "rtk_command" }
        assertTrue(rtk.command.contains("rtk git status"))
        assertFalse(rtk.command.contains("||"))
        assertFalse(rtk.command.contains("install"))
        assertFalse(rtk.command.contains("eval"))
    }
}
