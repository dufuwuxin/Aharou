package com.aharou.feature.agent.domain.workflow

/** Hard ceiling for LLM rounds in one user turn; leaves room for long tasks while bounding novel-tool-call loops. */
internal const val MAX_LLM_CALLS_PER_TURN = 64
internal const val LLM_CALL_BUDGET_EXCEEDED_CODE = "LLM_CALL_BUDGET_EXCEEDED"

internal fun isLlmCallBudgetExhausted(completedCalls: Int): Boolean =
    completedCalls >= MAX_LLM_CALLS_PER_TURN

internal fun StatefulAgentWorkflow.AgentSessionState.stopAtCallBudget() = copy(
    isFinished = true,
    error = "本轮已达到模型调用上限（${MAX_LLM_CALLS_PER_TURN} 次），已停止以避免任务无限运行。请检查当前进度后继续。",
    errorCode = LLM_CALL_BUDGET_EXCEEDED_CODE
)
