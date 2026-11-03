package com.aharou.feature.agent.domain.tool.custom

import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 工具注入方式：决定该工具是否进入某一轮的 tools 数组。
 *
 * 命名刻意保持中性（不带任何界面文案），文案在 `values/strings.xml` 与 `values-en/strings.xml` 的 `tools_inject_` 系列。
 */
@Serializable
enum class ToolInjectionMode {
    /** 每轮都注入（默认）。 */
    @SerialName("every_round")
    EVERY_ROUND,

    /** 仅当「当前轮用户消息」命中任一关键词时注入；关键词为空时按 [EVERY_ROUND] 处理。 */
    @SerialName("keyword")
    KEYWORD,

    /** 不进 tools 数组：模型看不到也调不到这个工具（想让模型偶尔用，改用 [KEYWORD]）。 */
    @SerialName("off")
    OFF
}

/** 自定义工具的参数类型：只支持 function-calling 能表达的四种基础标量。 */
@Serializable
enum class CustomToolParameterType {
    @SerialName("string")
    STRING,

    @SerialName("integer")
    INTEGER,

    @SerialName("number")
    NUMBER,

    @SerialName("boolean")
    BOOLEAN;

    /** 写进 JSON Schema 的 `type` 值。 */
    val jsonType: String
        get() = when (this) {
            STRING -> "string"
            INTEGER -> "integer"
            NUMBER -> "number"
            BOOLEAN -> "boolean"
        }

    /**
     * 映射到内置的受限类型枚举。[ParameterType] 没有 NUMBER，退化为 INTEGER——
     * 它只用于 `parameters` 表（工具说明 / 远程动作白名单），真正发给模型的 schema 由
     * [CustomCommandTool.toJsonSchema] 按参数表直接生成，`number` 不会丢。
     */
    fun toParameterType(): ParameterType = when (this) {
        STRING -> ParameterType.STRING
        INTEGER, NUMBER -> ParameterType.INTEGER
        BOOLEAN -> ParameterType.BOOLEAN
    }
}

/** 自定义工具的一个参数。[name] 同时是命令模板里 `{{name}}` 的占位符名。 */
@Serializable
data class CustomToolParameter(
    val name: String,
    val type: CustomToolParameterType = CustomToolParameterType.STRING,
    val description: String = "",
    val required: Boolean = false
) {
    /** 参数名要能直接当占位符用：与工具名同一套字符集，避免 `{{a b}}` 这类没法匹配的写法。 */
    val isValidName: Boolean
        get() = NAME_REGEX.matches(name)

    fun toToolParameter(): ToolParameter = ToolParameter(
        name = name,
        type = type.toParameterType(),
        description = description.ifBlank { name },
        required = required
    )

    companion object {
        val NAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    }
}

/**
 * 一条用户自定义工具：模型可见的 schema（[parameters]）+ 真实执行动作（[command] 模板）。
 *
 * 命令模板里的 `{{参数名}}` 会在执行前用 shell 单引号转义后替换（见 [CustomCommandTemplate]）。
 *
 * @param timeoutSeconds 单次执行超时，落盘与读取时都钳到 [MIN_TIMEOUT_SECONDS]..[MAX_TIMEOUT_SECONDS]。
 * @param injectionMode 注入方式，见 [ToolInjectionMode]。
 * @param keywords 关键词注入用的关键词（大小写不敏感、去空白、子串匹配当前轮用户消息）。
 */
@Serializable
data class CustomToolDefinition(
    val name: String,
    val description: String = "",
    val command: String = "",
    val parameters: List<CustomToolParameter> = emptyList(),
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val injectionMode: ToolInjectionMode = ToolInjectionMode.EVERY_ROUND,
    val keywords: List<String> = emptyList()
) {
    val isValidName: Boolean
        get() = NAME_REGEX.matches(name)

    /** 落盘 / 内存快照统一用它清洗：空白、超时区间、关键词去重都收敛到一处。 */
    fun normalized(): CustomToolDefinition = copy(
        name = name.trim(),
        description = description.trim(),
        command = command.trim(),
        parameters = parameters
            .mapNotNull { param ->
                val cleaned = param.copy(
                    name = param.name.trim(),
                    description = param.description.trim()
                )
                cleaned.takeIf { it.name.isNotEmpty() && it.isValidName }
            }
            .distinctBy { it.name },
        timeoutSeconds = timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
        keywords = normalizeKeywords(keywords)
    )

    fun toToolParameters(): Map<String, ToolParameter> {
        val map = LinkedHashMap<String, ToolParameter>()
        parameters.forEach { map[it.name] = it.toToolParameter() }
        return map
    }

    companion object {
        val NAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")

        const val DEFAULT_TIMEOUT_SECONDS = 60
        const val MIN_TIMEOUT_SECONDS = 1
        const val MAX_TIMEOUT_SECONDS = 600

        /** 关键词清洗：去首尾空白、丢空串、去重（保留原始大小写，匹配时再统一小写）。 */
        fun normalizeKeywords(raw: List<String>): List<String> =
            raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

/**
 * 内置 / MCP 工具的覆盖项：只允许改「描述」与「注入方式」，schema 仍由代码或服务端提供。
 *
 * 三个字段都为 null 表示「没有覆盖」，对应删除 `overrides.json` 里的这条记录。
 */
@Serializable
data class ToolOverride(
    val description: String? = null,
    val injectionMode: ToolInjectionMode? = null,
    val keywords: List<String>? = null
) {
    val isEmpty: Boolean
        get() = description.isNullOrBlank() && injectionMode == null && keywords.isNullOrEmpty()

    fun normalized(): ToolOverride = copy(
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        keywords = keywords?.let { CustomToolDefinition.normalizeKeywords(it) }
    )
}
