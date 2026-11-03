package com.aharou.feature.agent.domain.tool.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 独立补充，不改动既有模板 / 存储 / 注入的 32 条回归。只执行无害 printf。 */
class CustomCommandTemplateContextTest {
    private val parameters = listOf(CustomToolParameter(name = "value", required = true))
    private val payloads = listOf(
        "$(printf INJECTION_EXECUTED)",
        "`printf INJECTION_EXECUTED`",
        "a'\"b\\c; printf INJECTION_EXECUTED",
        "{{value}}",
        "ordinary value",
        "line one\nline two"
    )

    private fun render(template: String, value: String): String =
        (CustomCommandTemplate.renderForExecution(template, parameters, mapOf("value" to value)) as CustomCommandRender.Success).command

    private fun shell(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals("Command failed: $command\n$output", 0, process.waitFor())
        return output
    }

    @Test fun oldDoubleQuotedAlgorithmActuallyEvaluatesArgument() {
        val value = "$(printf INJECTION_EXECUTED)"
        val oldQuoted = "'" + value.replace("'", "'\"'\"'") + "'"
        assertEquals("'INJECTION_EXECUTED'", shell("printf '%s' \"$oldQuoted\""))
    }

    @Test fun valuesAreLiteralAcrossNormalSingleAndDoubleQuotes() {
        for (template in listOf("printf '%s' {{value}}", "printf '%s' '{{value}}'", "printf '%s' \"{{value}}\"")) {
            for (value in payloads) assertEquals("Template: $template", value, shell(render(template, value)))
        }
    }

    @Test fun concatenatedPlaceholderKeepsArgumentBoundary() {
        for (value in payloads) {
            assertEquals("prefix${value}suffix", shell(render("printf '%s' prefix{{value}}suffix", value)))
            assertEquals("prefix${value}suffix", shell(render("printf '%s' \"prefix{{value}}suffix\"", value)))
        }
    }

    @Test fun commandSubstitutionHasItsOwnQuoteContext() {
        for (value in payloads.filterNot { it.endsWith('\n') }) {
            assertEquals(value, shell(render("printf '%s' \"$(printf '%s' {{value}})\"", value)))
            assertEquals(value, shell(render("printf '%s' \"`printf '%s' {{value}}`\"", value)))
        }
    }

    @Test fun ordinaryUnquotedRenderingRemainsCompatible() {
        assertEquals("cat 'a b'", (CustomCommandTemplate.render("cat {{value}}", parameters, mapOf("value" to "a b")) as CustomCommandRender.Success).command)
        assertTrue(render("printf '%s' \"{{value}}\"", payloads.first()).startsWith("sh -c "))
    }
}
