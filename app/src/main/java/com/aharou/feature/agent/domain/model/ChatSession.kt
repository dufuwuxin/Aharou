package com.aharou.feature.agent.domain.model

/**
 * Agent 的工作模式。
 */
enum class AgentMode {
    BUILD, // 逐个确认模式：未授权操作弹窗确认
    PLAN,  // 计划模式，拦截修改类操作，只读/探索为主
    AUTO,  // 自动批准模式：工具调用一律不弹窗，安全防护（灾难性删除、敏感点按）仍然生效 —— Aharou 新会话默认
    ALLOW; // 完全放行：不弹窗，且安全防护一并关闭（灾难性删除、敏感操作、Shizuku 逐次确认都不再拦），仅用户手动可切换

    /** 宽松程度：数值越大越严。ALLOW（完全放行）< AUTO（自动批准）< BUILD（逐次确认）< PLAN（只读）。 */
    private val strictness: Int
        get() = when (this) {
            ALLOW -> -1
            AUTO -> 0
            BUILD -> 1
            PLAN -> 2
        }

    /**
     * 子会话模式收敛：取两者中更严的一档。
     * 子代理定义里的 mode 只能把权限收窄，不能放宽——否则父会话 PLAN 时派一个
     * `mode: auto` 的子代理，它就能绕过只读拦截直接改文件。
     */
    fun limitedBy(parent: AgentMode): AgentMode =
        if (strictness >= parent.strictness) this else parent
}

/**
 * 一次独立的聊天会话。消息通过 sessionId 归属到会话，切换会话即切换聊天历史。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: AgentMode = AgentMode.BUILD,
    /** 进入 PLAN 前的模式（如 AUTO）；退出 PLAN 时恢复，null 视为 BUILD。 */
    val modeBeforePlan: AgentMode? = null,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val isPinned: Boolean = false,
    /** 子代理会话：父会话 id；null 表示普通根会话。 */
    val parentId: String? = null,
    /** 子代理会话：派生子代理的类型（如 coder / researcher）；null 表示普通根会话。 */
    val subagentType: String? = null,
    /** 隐身模式：开启后本会话消息不落库、记忆不写入，关闭或切走即不留痕。 */
    val ghostMode: Boolean = false
)
