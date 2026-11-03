package com.aharou.feature.agent.domain.tool.custom

import com.aharou.core.util.shellQuote

/** 命令模板渲染结果：要么得到一条可执行命令，要么带着明确原因拒绝执行。 */
sealed interface CustomCommandRender {
    data class Success(val command: String) : CustomCommandRender
    data class Failure(val message: String, val code: String) : CustomCommandRender
}

/**
 * 自定义工具的命令模板渲染：把 `{{参数名}}` 换成 shell 单引号转义后的参数值。
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
     * 替换通过 [Regex.replace] 的一次性求值完成：参数值里即便含 `{{x}}` 也不会被二次展开，
     * 值一律整段套单引号，空格、单引号、中文都不需要调用方自己操心转义。
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

        val command = PLACEHOLDER_REGEX.replace(template) { match ->
            shellQuote(values[match.groupValues[1]].orEmpty())
        }
        return CustomCommandRender.Success(command)
    }
}
