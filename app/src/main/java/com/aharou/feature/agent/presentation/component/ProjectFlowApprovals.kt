package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aharou.feature.agent.presentation.AIAgentViewModel

@Composable
fun ProjectFlowApprovals(viewModel: AIAgentViewModel) {
    val request by viewModel.pendingToolPermission.collectAsStateWithLifecycle()
    val batch by viewModel.pendingPermissionBatch.collectAsStateWithLifecycle()
    val title by viewModel.pendingToolPermissionSessionTitle.collectAsStateWithLifecycle()
    val question by viewModel.pendingUserQuestion.collectAsStateWithLifecycle()
    val plan by viewModel.pendingPlanApproval.collectAsStateWithLifecycle()
    val planTitle by viewModel.pendingPlanApprovalSessionTitle.collectAsStateWithLifecycle()
    val config by com.aharou.core.config.confirm.ConfigConfirmationGate.pending.collectAsStateWithLifecycle()
    Column {
        if (request != null || batch != null) PermissionOverlayContent(batch, request,
            onBatchChoice = { choice -> batch?.let { viewModel.resolveToolPermissionBatch(it.id, choice) } },
            onRequestChoice = { choice -> request?.let { viewModel.resolveToolPermission(it.id, choice) } },
            sessionTitle = title, forceCollapse = false, isScrolling = false)
        question?.let { q -> AskUserQuestionPanel(q, { viewModel.resolveUserQuestion(q.id, it) },
            { viewModel.resolveUserQuestion(q.id, UserQuestionAnswer(emptyList())) }) }
        plan?.let { PlanApprovalPanel(it, viewModel::approvePlanAndBuild, viewModel::refinePlan, sessionTitle = planTitle) }
    }
    config?.let { change -> ConfigConfirmDialog(change,
        { com.aharou.core.config.confirm.ConfigConfirmationGate.userApprove(change.items) },
        { com.aharou.core.config.confirm.ConfigConfirmationGate.userReject() }) }
}
