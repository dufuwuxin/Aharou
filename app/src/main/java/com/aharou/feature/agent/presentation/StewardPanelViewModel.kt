package com.aharou.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.steward.StewardController
import com.aharou.feature.agent.domain.steward.StewardSettings
import com.aharou.feature.agent.domain.steward.StewardSettingsStore
import com.aharou.feature.agent.domain.steward.StewardState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 托管面板（输入栏入口按钮 + 底栏）的界面快照：**绑定到某一个会话**。
 *
 * 快照里的开关、轮数、状态都只属于 [sessionId] 那个会话；切会话时整体换掉，
 * 不会把上一个会话的托管状态显示到新会话上。
 */
data class StewardPanelUiState(
    val sessionId: String? = null,
    val state: StewardState = StewardState(),
    val maxLoops: Int = StewardSettings.DEFAULT_MAX_LOOPS,
    val unlimited: Boolean = false,
) {
    /**
     * 只认当前会话：ViewModel 的快照还停在别的会话上（刚切过来、绑定还没跑）时，
     * 一律按「未开启」显示，宁可短暂显示关闭，也不把别的会话的开关串过来。
     */
    fun forSession(sessionId: String): StewardPanelUiState =
        if (this.sessionId == sessionId) this else StewardPanelUiState(sessionId = sessionId)
}

/**
 * 智能托管面板的状态与操作入口，按会话读写。
 *
 * 判定与轮次记账都在 [StewardController] 里，这里只做「界面 → 控制器」的转发：
 * 把界面传来的 sessionId 绑成当前会话，再把该会话的开关/轮数/状态吐给界面。
 * 开关打开时的基准指令（本会话最后一条用户消息）也在这里解析。
 */
@HiltViewModel
class StewardPanelViewModel @Inject constructor(
    private val controller: StewardController,
    private val settings: StewardSettingsStore,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
) : ViewModel() {

    /** 界面当前在看的会话。 */
    private val boundSessionId = MutableStateFlow<String?>(null)

    /** 当前会话的轮数上限 / 不限轮数：存在 SharedPreferences 里不会自己发流，切会话时重读一次。 */
    private val prefs = MutableStateFlow(SessionPrefs())

    val uiState: StateFlow<StewardPanelUiState> =
        combine(controller.state, boundSessionId, prefs) { states, sessionId, local ->
            StewardPanelUiState(
                sessionId = sessionId,
                state = sessionId?.let { states[it] } ?: StewardState(),
                maxLoops = sessionId?.let { settings.maxLoops(it) } ?: local.maxLoops,
                unlimited = sessionId?.let { settings.unlimited(it) } ?: local.unlimited,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StewardPanelUiState())

    /**
     * 界面把当前会话告诉面板：切会话时开关/轮数/状态整体换成新会话的。
     *
     * 动作入口里也会先调一次，免得界面刚切换还没重组时，点击落到上一个会话上。
     */
    fun bindSession(sessionId: String) {
        if (sessionId.isBlank() || boundSessionId.value == sessionId) return
        boundSessionId.value = sessionId
        prefs.value = SessionPrefs(settings.maxLoops(sessionId), settings.unlimited(sessionId))
    }

    /**
     * 开关当前会话的托管。打开时以本会话最后一条用户消息为基准——那正是这一轮任务的起点；
     * 会话里还没有用户消息就开不了（没有基准就无从判断完成没）。
     */
    fun setEnabled(on: Boolean, sessionId: String) {
        bindSession(sessionId)
        if (!on) {
            controller.disable(sessionId)
            return
        }
        val revision = controller.enable(sessionId, "")
        viewModelScope.launch {
            val anchor = messagePersistenceUseCase.getMessagesOnce(sessionId)
                .lastOrNull {
                    it.role == MessageRole.USER.name && it.content.isNotBlank() &&
                        !it.content.startsWith(BACKGROUND_NOTIFICATION_PREFIX)
                }?.content?.trim().orEmpty()
            controller.bindAnchor(sessionId, anchor, revision)
        }
    }

    fun setMaxLoops(sessionId: String, value: Int) {
        bindSession(sessionId)
        controller.setMaxLoops(sessionId, value)
        prefs.value = prefs.value.copy(maxLoops = settings.maxLoops(sessionId))
    }

    fun setUnlimited(sessionId: String, value: Boolean) {
        bindSession(sessionId)
        controller.setUnlimited(sessionId, value)
        prefs.value = prefs.value.copy(unlimited = value)
    }

    private data class SessionPrefs(
        val maxLoops: Int = StewardSettings.DEFAULT_MAX_LOOPS,
        val unlimited: Boolean = false,
    )

    private companion object {
        const val TAG = "StewardPanelViewModel"
    }
}
