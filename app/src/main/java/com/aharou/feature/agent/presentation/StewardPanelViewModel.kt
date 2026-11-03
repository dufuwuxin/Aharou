package com.aharou.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.steward.StewardController
import com.aharou.feature.agent.domain.steward.StewardSettings
import com.aharou.feature.agent.domain.steward.StewardState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 智能托管面板（输入栏入口按钮 + 底栏）的状态与操作入口。
 *
 * 判定与轮次记账都在 [StewardController] 里，这里只做「界面 → 控制器」的转发，
 * 外加把开关打开时的基准指令（本会话最后一条用户消息）解析出来。
 */
@HiltViewModel
class StewardPanelViewModel @Inject constructor(
    private val controller: StewardController,
    private val settings: StewardSettings,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
) : ViewModel() {

    val state: StateFlow<StewardState> = controller.state

    private val _maxLoops = MutableStateFlow(settings.maxLoops)
    val maxLoops: StateFlow<Int> = _maxLoops.asStateFlow()

    private val _unlimited = MutableStateFlow(settings.unlimited)
    val unlimited: StateFlow<Boolean> = _unlimited.asStateFlow()

    /**
     * 开关托管。打开时以本会话最后一条用户消息为基准——那正是这一轮任务的起点；
     * 空会话先开启等待，首条实际请求到来时再绑定基准。
     */
    fun setEnabled(on: Boolean, sessionId: String) {
        if (!on) {
            controller.disable()
            return
        }
        val revision = controller.enable("", sessionId)
        viewModelScope.launch {
            val anchor = messagePersistenceUseCase.getMessagesOnce(sessionId)
                .lastOrNull {
                    it.role == MessageRole.USER.name && it.content.isNotBlank() &&
                        !it.content.startsWith(BACKGROUND_NOTIFICATION_PREFIX)
                }
                ?.content?.trim().orEmpty()
            controller.bindAnchor(sessionId, anchor, revision)
        }
    }

    fun setMaxLoops(value: Int) {
        controller.setMaxLoops(value)
        _maxLoops.value = settings.maxLoops
    }

    fun setUnlimited(value: Boolean) {
        controller.setUnlimited(value)
        _unlimited.value = value
    }

}