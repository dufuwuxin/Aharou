package com.aharou.feature.agent.domain.tool.subagent

/** Conditions are evaluated from the bound workspace, never inferred from an agent's prose. */
object TeamStageCondition {
    enum class Result { RUN, SKIP, UNKNOWN }

    fun evaluate(condition: String, files: Set<String>?): Result {
        if (condition == "always") return Result.RUN
        if (condition == "never") return Result.SKIP
        val suffix = when (condition) {
            "rust" -> ".rs"
            "python" -> ".py"
            "kotlin" -> ".kt"
            else -> return Result.UNKNOWN
        }
        if (files == null) return Result.UNKNOWN
        val excluded = setOf(".git", ".aharou", ".venv", "venv", "node_modules", "target", "build")
        return if (files.any { path ->
            path.split('/').none { it in excluded } &&
                (path.endsWith(suffix, true) || condition == "rust" && path.substringAfterLast('/') == "Cargo.toml" ||
                    condition == "python" && path.substringAfterLast('/') == "pyproject.toml")
        }) Result.RUN else Result.SKIP
    }
}
