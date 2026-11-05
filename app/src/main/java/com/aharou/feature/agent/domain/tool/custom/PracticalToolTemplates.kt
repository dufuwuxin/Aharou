package com.aharou.feature.agent.domain.tool.custom

/** Drafts only: selecting one fills the editor; nothing is registered until the user saves. */
object PracticalToolTemplates {
    val all: List<CustomToolDefinition> = listOf(
        CustomToolDefinition(
            name = "build_android", description = "Build one Android Gradle task without installing tools. Returns the last 80 lines, exit code and full log path. Raw logs may contain sensitive data; no automatic redaction.",
            command = "aharou-build android {{task}}",
            parameters = listOf(CustomToolParameter("task", description = "One Gradle task, e.g. :app:assembleArmsoloDebug", required = true)),
            timeoutSeconds = 600, injectionMode = ToolInjectionMode.KEYWORD,
            keywords = listOf("Android", "Gradle", "Kotlin", "APK")
        ),
        CustomToolDefinition(
            name = "build_rust", description = "Run cargo build without installing tools. Returns the last 80 lines, exit code and full log path. Raw logs may contain sensitive data; no automatic redaction.",
            command = "aharou-build rust",
            timeoutSeconds = 600, injectionMode = ToolInjectionMode.KEYWORD,
            keywords = listOf("Rust", "cargo")
        ),
        CustomToolDefinition(
            name = "build_python", description = "Run python3 -m pip wheel --no-deps . -w dist without installing tools. Returns the last 80 lines, exit code and full log path. Raw logs may contain sensitive data; no automatic redaction.",
            command = "aharou-build python",
            timeoutSeconds = 600, injectionMode = ToolInjectionMode.KEYWORD,
            keywords = listOf("Python", "wheel")
        ),
        CustomToolDefinition(
            name = "build_logs", description = "Read the current workspace's latest completed build: last 80 lines, exit code and full log path. Raw logs may contain sensitive data; no automatic redaction.",
            command = "aharou-build logs",
            timeoutSeconds = 600, injectionMode = ToolInjectionMode.KEYWORD,
            keywords = listOf("构建日志", "编译日志", "build log", "cargo log", "Gradle log")
        ),
        CustomToolDefinition(
            name = "git_status", description = "Show repository status without changing files.",
            command = "git -C {{path}} status --short --branch",
            parameters = listOf(path()), injectionMode = ToolInjectionMode.KEYWORD,
            keywords = listOf("git status", "repository status", "仓库状态")
        ),
        CustomToolDefinition(
            name = "git_diff", description = "Show unstaged changes without external diff helpers.",
            command = "git -C {{path}} --no-pager diff --no-ext-diff --no-textconv --",
            parameters = listOf(path()), injectionMode = ToolInjectionMode.KEYWORD,
            keywords = listOf("git diff", "changes", "差异")
        ),
        CustomToolDefinition(
            name = "git_log", description = "Show the last 20 commits.",
            command = "git -C {{path}} --no-pager log -20 --oneline",
            parameters = listOf(path()), injectionMode = ToolInjectionMode.KEYWORD,
            keywords = listOf("git log", "commit history", "提交历史")
        ),
        CustomToolDefinition(
            name = "find_files", description = "Find files by glob name under a directory.",
            command = "p={{path}}; case \"\$p\" in /*|./*|../*) ;; *) p=./\"\$p\" ;; esac; find \"\$p\" -type f -name {{pattern}} -print",
            parameters = listOf(path(), CustomToolParameter("pattern", description = "Filename glob, e.g. *.kt", required = true)),
            injectionMode = ToolInjectionMode.KEYWORD,
            keywords = listOf("find files", "find_files", "查找文件")
        ),
        CustomToolDefinition(
            name = "rtk_command", description = "Run an installed RTK filter: git status, git diff, git log or find. No automatic install or fallback. Use native tools to inspect raw output.",
            // Fixed dispatch prevents the operation argument becoming arbitrary shell source.
            command = "case {{operation}} in status) rtk git status ;; diff) rtk git diff ;; log) rtk git log ;; find) p={{path}}; case \"\$p\" in /*|./*|../*) ;; *) p=./\"\$p\" ;; esac; rtk find \"\$p\" ;; *) printf '%s\\n' 'Use status, diff, log or find' >&2; exit 2 ;; esac",
            parameters = listOf(
                CustomToolParameter("operation", description = "One of: status, diff, log, find", required = true),
                CustomToolParameter("path", description = "Directory used by the find operation; otherwise ignored")
            ), injectionMode = ToolInjectionMode.KEYWORD,
            keywords = listOf("rtk", "filtered output", "输出过滤")
        )
    )

    private fun path() = CustomToolParameter("path", description = "Repository/directory path (use . for current directory)", required = true)
}
