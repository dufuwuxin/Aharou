package com.aharou.feature.localmodel.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.localmodel.domain.LocalModelManager
import com.aharou.feature.localmodel.domain.LocalModelState
import com.aharou.feature.localmodel.domain.LocalModelStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 端侧模型的设置页状态：列表落盘状态（[statuses]）＋ 当前这次操作的状态（[state]）。
 *
 * 两者分工：[statuses] 是「磁盘上实际有什么」，只在 [refresh] 时重读；[state] 是「这一次点下载走到哪了」，
 * 由 [LocalModelManager.download] 的回调实时推进。界面同时看两者——进度看 [state]，
 * 「已就绪 / 未下载 / 未下完」看 [statuses]，因为进度回调结束不代表文件已经就位。
 */
@HiltViewModel
class LocalModelViewModel @Inject constructor(
    private val manager: LocalModelManager,
) : ViewModel() {

    /** 全部模型的落盘状态（顺序与 [com.aharou.feature.localmodel.domain.LocalModels.ALL] 一致）。 */
    private val _statuses = MutableStateFlow(manager.statusAll())
    val statuses: StateFlow<List<LocalModelStatus>> = _statuses.asStateFlow()

    /** 当前这次下载/校验的状态；没有操作在跑时是 [LocalModelState.Idle]。 */
    private val _state = MutableStateFlow<LocalModelState>(LocalModelState.Idle)
    val state: StateFlow<LocalModelState> = _state.asStateFlow()

    /**
     * 本机能否跑端侧推理（只看 ABI）。
     *
     * 不放进 StateFlow：它由 Build 常量决定，装上就不会变，界面上没机会也不该刷新它。
     */
    val supported: Boolean = manager.isSupportedOnThisDevice()

    /** 重读磁盘上的落盘状态；下载/删除结束后调用，让列表跟上实体。 */
    fun refresh() {
        _statuses.value = manager.statusAll()
    }

    /**
     * 下载并安装指定模型。
     *
     * 同一模型已经在下/在验校时直接返回：再起一个协程只会被 Manager 的 mutex 排在后面，
     * 界面上表现为「进度先跳回去再重新走」，不如不响应重复点按。
     */
    fun download(id: String) {
        val current = _state.value
        if (current is LocalModelState.Downloading && current.id == id) return
        if (current is LocalModelState.Verifying && current.id == id) return
        val spec = manager.spec(id) ?: return
        viewModelScope.launch {
            try {
                manager.download(spec, onState = { _state.value = it })
            } catch (e: CancellationException) {
                // 取消不是失败：半成品留着续传，界面回到「未下完」而不是「下载失败」
                _state.value = LocalModelState.Idle
                throw e
            } catch (e: Exception) {
                // Manager 内部已回调过 Failed（文案已本地化），但「没走到回调就抛」的场合要靠这里兜住，
                // 否则界面会一直停在下载中
                _state.value = LocalModelState.Failed(id, e.message.orEmpty())
            } finally {
                refresh()
            }
        }
    }

    /** 删除该模型的全部落盘数据（正式文件 + 半成品），并刷新列表。 */
    fun delete(id: String) {
        manager.delete(id)
        // 删掉的正是刚就绪的那个：把操作状态也归零，否则界面还留着一次「已就绪」
        val current = _state.value
        if (current is LocalModelState.Ready && current.id == id) {
            _state.value = LocalModelState.Idle
        }
        refresh()
    }

    /** 取消正在进行的下载；取消后由协程自己把状态切回 [LocalModelState.Idle]。 */
    fun cancel() {
        manager.cancel()
    }
}
