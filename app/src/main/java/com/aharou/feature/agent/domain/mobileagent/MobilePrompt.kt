package com.aharou.feature.agent.domain.mobileagent

/** Per-step input data for the memory-backed mobile-operation prompt. */
object MobilePrompt {
    const val TOOL_NAME = "mobile_act"

    fun userText(template: String, instruction: String, progress: String, screen: String?, nodes: String?): String {
        return Regex("\\{\\{(GOAL|SCREEN|PROGRESS|NODES)\\}\\}").replace(template) { match ->
            when (match.groupValues[1]) {
                "GOAL" -> instruction
                "SCREEN" -> screen?.let { "$it 像素" } ?: "未知"
                "PROGRESS" -> progress.ifBlank { "（还没有）" }
                else -> nodes?.takeIf { it.isNotBlank() }?.let { "屏幕节点数据（像素坐标）：\n$it" }.orEmpty()
            }
        }
    }

}
