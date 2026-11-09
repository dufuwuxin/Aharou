package com.aharou.feature.agent.domain.prompt

internal object PromptVariableExpander {
    fun expandNative(text: String, resolve: (String) -> String): String {
        val values = mutableMapOf<String, String>()
        return Regex("\\{\\{AICODE_([A-Z_]+)\\}\\}").replace(text) { match ->
            values.getOrPut(match.groupValues[1]) { resolve(match.groupValues[1]) }
        }
    }
}
