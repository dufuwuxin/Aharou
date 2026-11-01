package com.aharou.feature.agent.domain.tool.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令模板渲染与引号转义。纯 Kotlin/JUnit（不用 MockK：本容器里它跑不起来）。
 */
class CustomCommandTemplateTest {

    private val params = listOf(
        CustomToolParameter(name = "path", type = CustomToolParameterType.STRING, required = true),
        CustomToolParameter(name = "flag", type = CustomToolParameterType.STRING, required = false)
    )

    private fun render(template: String, values: Map<String, String?>) =
        CustomCommandTemplate.render(template, params, values)

    @Test
    fun render_replacesPlaceholderAndQuotesValue() {
        val result = render("cat {{path}}", mapOf("path" to "a b.txt"))
        assertTrue(result is CustomCommandRender.Success)
        assertEquals("cat 'a b.txt'", (result as CustomCommandRender.Success).command)
    }

    @Test
    fun render_escapesSingleQuote() {
        val result = render("echo {{path}}", mapOf("path" to "it's here"))
        assertTrue(result is CustomCommandRender.Success)
        // POSIX 单引号转义：'it'"'"'s here'
        assertEquals("echo 'it'\"'\"'s here'", (result as CustomCommandRender.Success).command)
    }

    @Test
    fun render_keepsChineseVerbatim() {
        val result = render("echo {{path}}", mapOf("path" to "中文 路径"))
        assertTrue(result is CustomCommandRender.Success)
        assertEquals("echo '中文 路径'", (result as CustomCommandRender.Success).command)
    }

    @Test
    fun render_handlesRepeatedAndSpacedPlaceholders() {
        val result = render("cp {{ path }} {{path}}", mapOf("path" to "/tmp/x"))
        assertTrue(result is CustomCommandRender.Success)
        assertEquals("cp '/tmp/x' '/tmp/x'", (result as CustomCommandRender.Success).command)
    }

    @Test
    fun render_optionalMissingBecomesEmptyLiteral() {
        val result = render("git log {{path}} {{flag}}", mapOf("path" to "src"))
        assertTrue(result is CustomCommandRender.Success)
        assertEquals("git log 'src' ''", (result as CustomCommandRender.Success).command)
    }

    @Test
    fun render_doesNotReExpandValue() {
        val result = render("echo {{path}}", mapOf("path" to "{{flag}}"))
        assertTrue(result is CustomCommandRender.Success)
        assertEquals("echo '{{flag}}'", (result as CustomCommandRender.Success).command)
    }

    @Test
    fun render_missingRequiredArgumentFails() {
        val result = render("cat {{path}}", emptyMap())
        assertTrue(result is CustomCommandRender.Failure)
        val failure = result as CustomCommandRender.Failure
        assertEquals(CustomCommandTemplate.CODE_MISSING_ARGUMENT, failure.code)
        assertTrue(failure.message.contains("path"))
    }

    @Test
    fun render_blankRequiredArgumentFails() {
        val result = render("cat {{path}}", mapOf("path" to "   "))
        assertTrue(result is CustomCommandRender.Failure)
        assertEquals(CustomCommandTemplate.CODE_MISSING_ARGUMENT, (result as CustomCommandRender.Failure).code)
    }

    @Test
    fun render_templateWithoutPlaceholderRunsAsIs() {
        // 固定命令（如 git status / df -h）也是合法工具：没参数、没占位符都应该照原样跑
        val result = CustomCommandTemplate.render("git status", emptyList(), emptyMap())
        assertEquals("git status", (result as CustomCommandRender.Success).command)
    }

    @Test
    fun render_undeclaredPlaceholderFails() {
        val result = render("cat {{pth}}", mapOf("pth" to "x"))
        assertTrue(result is CustomCommandRender.Failure)
        val failure = result as CustomCommandRender.Failure
        assertEquals(CustomCommandTemplate.CODE_UNKNOWN_PLACEHOLDER, failure.code)
        assertTrue(failure.message.contains("pth"))
    }

    @Test
    fun render_blankTemplateFails() {
        val result = render("   ", emptyMap())
        assertTrue(result is CustomCommandRender.Failure)
        assertEquals(CustomCommandTemplate.CODE_EMPTY_TEMPLATE, (result as CustomCommandRender.Failure).code)
    }

    @Test
    fun placeholders_listsDistinctNamesInOrder() {
        assertEquals(
            listOf("a", "b"),
            CustomCommandTemplate.placeholders("run {{a}} then {{ a }} and {{b}}")
        )
    }
}
