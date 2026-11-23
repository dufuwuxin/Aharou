package com.aharou.feature.agent.domain.container

object ContainerEnvValueRedactor {
    const val MASK = "[已隐藏]"

    data class Result(val text: String, val hit: Boolean)

    fun redact(text: String, variables: Map<String, String>): Result {
        if (text.isEmpty() || variables.isEmpty()) return Result(text, false)
        val values = variables.values.filter(String::isNotBlank).flatMap { value ->
            buildList {
                add(value)
                add(jsonEscape(value))
                value.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach(::add)
            }
        }.distinct().sortedByDescending(String::length)
        if (values.isEmpty()) return Result(text, false)
        var result = text
        values.forEach { value -> result = result.replace(value, MASK) }
        return Result(result, result != text)
    }

    private fun jsonEscape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
