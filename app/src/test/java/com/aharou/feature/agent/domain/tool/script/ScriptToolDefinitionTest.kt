package com.aharou.feature.agent.domain.tool.script

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptToolDefinitionTest {
    private fun definition(command: String = "cat") = ScriptToolDefinition("inspect", "Read input", command)

    @Test fun validDefinitionHasVersionedIdentity() {
        assertTrue(definition().toolName.matches(Regex("script_inspect_[0-9a-f]{32}")))
    }

    @Test fun commandChangeChangesIdentity() {
        assertNotEquals(definition().toolName, definition("cat -n").toolName)
    }

    @Test fun descriptionAndEnableDoNotChangeCommandIdentity() {
        val original = definition()
        assertEquals(original.toolName, original.copy(description = "Changed", enabled = false).toolName)
    }

    @Test fun longestNameFitsToolNameLimit() {
        assertEquals(64, definition().copy(name = "a".repeat(24)).toolName.length)
    }

    @Test(expected = IllegalArgumentException::class) fun invalidNameRejected() {
        definition().copy(name = "bad.name")
    }

    @Test(expected = IllegalArgumentException::class) fun overlongNameRejected() {
        definition().copy(name = "a".repeat(25))
    }

    @Test(expected = IllegalArgumentException::class) fun emptyDescriptionRejected() {
        definition().copy(description = " ")
    }

    @Test(expected = IllegalArgumentException::class) fun blankCommandRejected() {
        definition("\n ")
    }

    @Test(expected = IllegalArgumentException::class) fun nulCommandRejected() {
        definition("cat\u0000")
    }

    @Test(expected = IllegalArgumentException::class) fun duplicateNamesRejected() {
        ScriptToolDefinition.validateAll(listOf(definition(), definition("pwd")))
    }

    @Test fun differentNamesAccepted() {
        ScriptToolDefinition.validateAll(listOf(definition(), definition().copy(name = "other")))
    }

    @Test fun jsonRoundTripPreservesDefinitions() {
        val original = listOf(definition("cat\nprintf '%s' 'done'").copy(enabled = false))
        assertEquals(original, Json.decodeFromString<List<ScriptToolDefinition>>(Json.encodeToString(original)))
    }

    @Test fun jsonIsStdinDataNotShellSource() {
        val input = buildJsonObject {
            put("quoted", JsonPrimitive("single ' double \" newline\nnext"))
            put("substitution", JsonPrimitive("$(printf injected) `printf injected` \$HOME \\ end"))
            put("unicode", JsonPrimitive("中文"))
        }
        val process = ProcessBuilder("sh").start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use {
            it.write(definition().executionCommand(input))
            it.newLine()
        }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        assertEquals(0, process.waitFor())
        assertEquals(input.toString(), output)
        assertFalse(output.contains("\"substitution\":\"injected"))
    }

    @Test fun savedCommandRetainsQuotesAndNewlines() {
        val input = buildJsonObject { put("text", JsonPrimitive("`printf bad` $(printf bad)")) }
        val script = definition("cat\nprintf '\\n%s' 'saved command'")
        val process = ProcessBuilder("sh").start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use {
            it.write(script.executionCommand(input))
            it.newLine()
        }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        assertEquals(0, process.waitFor())
        assertEquals(input.toString() + "\nsaved command", output)
    }
}
