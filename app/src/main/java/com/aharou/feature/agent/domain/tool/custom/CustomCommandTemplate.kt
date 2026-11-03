package com.aharou.feature.agent.domain.tool.custom

import com.aharou.core.util.shellQuote

/** 命令模板渲染结果：要么得到一条可执行命令，要么带着明确原因拒绝执行。 */
sealed interface CustomCommandRender {
    data class Success(val command: String) : CustomCommandRender
    data class Failure(val message: String, val code: String) : CustomCommandRender
}

/**
 * 自定义工具的命令模板渲染：按占位符所在 shell 引用上下文转义参数值。
 *
 * 几条硬规则（都是「宁可报错也不执行半条命令」的体现）：
 * - 模板为空 → 拒绝；
 * - 模板引用了未在参数表里声明的占位符 → 拒绝（多半是笔误，例如把 `{{path}}` 写成 `{{pth}}`）；
 * - 必需参数缺失或全是空白 → 拒绝；
 * - 可选参数缺失 → 替换成空字符串字面量 `''`，保持命令结构完整；
 * - 模板里一个占位符都没有 → 允许（固定命令，如 `df -h`，这也是合法工具）。
 *
 * 纯函数，不碰任何 Android / 协程 API，便于单测覆盖转义细节。
 */
object CustomCommandTemplate {

    /** `{{ name }}` 形式（花括号到参数名之间允许空格），参数名与工具名同一套字符集。 */
    val PLACEHOLDER_REGEX = Regex("""\{\{\s*([A-Za-z0-9_-]+)\s*\}\}""")

    const val CODE_EMPTY_TEMPLATE = "CUSTOM_TOOL_EMPTY_COMMAND"
    const val CODE_UNKNOWN_PLACEHOLDER = "CUSTOM_TOOL_UNKNOWN_PLACEHOLDER"
    const val CODE_MISSING_ARGUMENT = "CUSTOM_TOOL_MISSING_ARGUMENT"

    /** 模板里出现的占位符名（按首次出现顺序去重），供界面做「模板用到了哪些参数」提示。 */
    fun placeholders(template: String): List<String> =
        PLACEHOLDER_REGEX.findAll(template).map { it.groupValues[1] }.distinct().toList()

    /**
     * 渲染命令。[values] 按参数名给字符串值（已由调用方从工具入参里取好）。
     *
     * 只扫描模板，不重新扫描参数：值里的 `{{x}}` 不会二次展开。
     * 无引用时使用 shellQuote；单 / 双引号中只转义该引用上下文的特殊字符。
     */
    fun render(
        template: String,
        parameters: List<CustomToolParameter>,
        values: Map<String, String?>
    ): CustomCommandRender {
        if (template.isBlank()) {
            return CustomCommandRender.Failure("自定义工具的执行命令为空，无法执行。", CODE_EMPTY_TEMPLATE)
        }

        val used = placeholders(template)
        val declared = parameters.map { it.name }.toSet()
        val unknown = used.firstOrNull { it !in declared }
        if (unknown != null) {
            return CustomCommandRender.Failure(
                "执行命令引用了未声明的占位符 {{$unknown}}：请在参数里补上它，或改掉模板里的拼写。",
                CODE_UNKNOWN_PLACEHOLDER
            )
        }

        val missing = parameters.firstOrNull { it.required && values[it.name].isNullOrBlank() }
        if (missing != null) {
            return CustomCommandRender.Failure(
                "缺少必需参数: ${missing.name}（请在调用本工具时提供该参数）",
                CODE_MISSING_ARGUMENT
            )
        }

        return CustomCommandRender.Success(renderQuoted(template, values))
    }

    /**
     * 执行入口：模板作为 sh -c 的脚本，参数仅通过 argv 绑定。即使模板含 heredoc 等复杂语法，
     * 用户输入也不会进入 shell 源码；引用状态只决定参数引用如何拼接，不决定输入能否执行。
     * render 保留原有可读预览与回归字符串；真实审批 / 执行统一使用此命令。
     */
    fun renderForExecution(
        template: String,
        parameters: List<CustomToolParameter>,
        values: Map<String, String?>
    ): CustomCommandRender {
        val checked = render(template, parameters, values)
        if (checked is CustomCommandRender.Failure) return checked
        val used = placeholders(template)
        if (used.isEmpty()) return checked
        val bindings = used.withIndex().associate { it.value to it.index + 1 }
        val script = renderQuoted(template, emptyMap(), bindings)
        val arguments = used.joinToString(" ") { shellQuote(values[it].orEmpty()) }
        return CustomCommandRender.Success("sh -c ${shellQuote(script)} custom-tool $arguments")
    }

    private data class ShellFrame(var quote: Char? = null, val closing: Char? = null, var depth: Int = 0)

    /**
     * POSIX shell 的单引号中只需处理单引号；双引号中必须处理 $、反引号、双引号与反斜线。
     * $() / 反引号内部是新的命令引用域，不能沿用外层双引号状态。
     * 旧式反引号先进行一次反斜线处理，故其内的替换还需逐层保护 $、`、\。
     */
    private fun renderQuoted(
        template: String,
        values: Map<String, String?>,
        bindings: Map<String, Int>? = null
    ): String {
        val frames = mutableListOf(ShellFrame())
        val result = StringBuilder()
        var index = 0
        while (index < template.length) {
            val frame = frames.last()
            val match = PLACEHOLDER_REGEX.find(template, index)?.takeIf { it.range.first == index }
            if (match != null) {
                val value = values[match.groupValues[1]].orEmpty()
                val reference = bindings?.get(match.groupValues[1])?.let { "\${${it}}" }
                var literal = if (reference != null) when (frame.quote) {
                    '\'' -> "'\"$reference\"'"
                    '"' -> reference
                    else -> "\"$reference\""
                } else when (frame.quote) {
                    '\'' -> value.replace("'", "'\"'\"'")
                    '"' -> value.replace("\\", "\\\\").replace("$", "\\$")
                        .replace("`", "\\`").replace("\"", "\\\"")
                    else -> shellQuote(value)
                }
                repeat(frames.count { it.closing == '`' }) {
                    literal = literal.replace("\\", "\\\\").replace("$", "\\$").replace("`", "\\`")
                }
                result.append(literal)
                index = match.range.last + 1
                continue
            }
            val char = template[index]
            if (char == '\\' && frame.quote != '\'' && index + 1 < template.length) {
                // A backslash before a placeholder escapes its opening brace, not the quoted value.
                if (PLACEHOLDER_REGEX.find(template, index + 1)?.range?.first == index + 1) {
                    index++
                } else {
                    result.append(char).append(template[index + 1])
                    index += 2
                }
                continue
            }
            when {
                frame.quote == '\'' -> if (char == '\'') frame.quote = null
                char == '\'' && frame.quote == null -> frame.quote = '\''
                char == '"' -> frame.quote = if (frame.quote == '"') null else '"'
                char == '$' && template.getOrNull(index + 1) == '(' -> {
                    result.append("$(")
                    frames.add(ShellFrame(closing = ')'))
                    index += 2
                    continue
                }
                char == '`' && frame.closing == '`' -> frames.removeAt(frames.lastIndex)
                char == '`' -> frames.add(ShellFrame(closing = '`'))
                char == '(' && frame.quote == null && frame.closing == ')' -> frame.depth++
                char == ')' && frame.quote == null && frame.closing == ')' -> {
                    if (frame.depth == 0) frames.removeAt(frames.lastIndex) else frame.depth--
                }
            }
            result.append(char)
            index++
        }
        return result.toString()
    }
}
