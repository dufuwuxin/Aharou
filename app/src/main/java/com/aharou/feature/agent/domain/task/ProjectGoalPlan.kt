package com.aharou.feature.agent.domain.task

/** Horizon is a bounded execution budget, never permission to chat indefinitely. */
object ProjectGoalPlan {
    val horizons = listOf("short", "medium", "long")
    fun taskBudget(horizon: String): Int = when (horizon) { "long" -> 32; "medium" -> 16; else -> 8 }
    fun timeBudgetMs(horizon: String): Long = when (horizon) {
        "long" -> 120L * 60_000L
        "medium" -> 60L * 60_000L
        else -> 30L * 60_000L
    }

    /** Even all accepted tasks cannot prove an open-ended project goal is complete. */
    fun checkpoint(goal: String, acceptance: String, tasks: List<ProjectTask>, budgetReached: Boolean = false): String {
        val executable = tasks.filterNot { it.isRequirementBranch }
        return when {
            budgetReached -> "BUDGET_REACHED"
            goal.isBlank() || acceptance.isBlank() -> "GOAL_CONFIRMATION_REQUIRED"
            executable.isEmpty() -> "NEXT_STAGE_CONFIRMATION_REQUIRED"
            executable.any { !ProjectWorkflowPlan.delivered(it) } -> "TASKS_PENDING"
            executable.any { it.status != ProjectTaskStatus.DONE } -> "TASK_ACCEPTANCE_REQUIRED"
            else -> "GOAL_ACCEPTANCE_REQUIRED"
        }
    }

    fun label(reason: String): String = when (reason) {
        "MEMBER_TIMEOUT" -> "成员执行超时，待确认重试或调整阶段"
        "BUDGET_REACHED" -> "运行预算已用完，待确认下一阶段"
        "GOAL_CONFIRMATION_REQUIRED" -> "总目标或验收标准待确认"
        "NEXT_STAGE_CONFIRMATION_REQUIRED" -> "待确认下一阶段计划"
        "TASKS_PENDING" -> "仍有任务受依赖阻塞或等待处理"
        "TASK_ACCEPTANCE_REQUIRED" -> "成员已交付，任务待验收"
        "GOAL_ACCEPTANCE_REQUIRED" -> "任务已验收，总目标仍待人工验收"
        else -> "待确认"
    }

    fun context(goal: String, acceptance: String, horizon: String): String =
        "Overall goal:\n$goal\nProject acceptance:\n$acceptance\nHorizon: $horizon. " +
            "A member reply is not project completion. Report evidence and unresolved next-stage work; never invent acceptance."
}
