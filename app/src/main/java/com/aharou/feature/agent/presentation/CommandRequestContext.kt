package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.domain.command.SlashCommandContext
import com.aharou.feature.agent.domain.steward.StewardTurnPolicy

/** 本地命令开启模型轮时使用原请求快照，不能重新读取当前界面的会话或附件。 */
internal class CommandRequestContext(
    delegate: SlashCommandContext,
    private val request: QueuedRequest,
    private val launchModel: (QueuedRequest) -> Unit,
) : SlashCommandContext by delegate {
    override fun initProject(prompt: String) {
        launchModel(request.copy(
            request = prompt,
            modelRequest = StewardTurnPolicy.skillModelRequest(request.request, request.modelRequest, prompt),
            isAutoTrigger = true,
            persistUserMessage = false,
        ))
    }
}
