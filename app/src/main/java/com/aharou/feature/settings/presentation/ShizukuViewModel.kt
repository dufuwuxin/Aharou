package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.shell.HostShellManager
import com.aharou.feature.agent.domain.shell.HostShellMode
import com.aharou.feature.agent.domain.shizuku.ShizukuManager
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shizuku 的设置页状态与操作入口。 */
@HiltViewModel
class ShizukuViewModel @Inject constructor(
    private val shizukuManager: ShizukuManager,
    private val hostShell: HostShellManager,
) : ViewModel() {

    val state: StateFlow<ShizukuState> = shizukuManager.state

    /** 实际执行的通道：Shizuku / 不可用。 */
    val mode: StateFlow<HostShellMode> = hostShell.mode

    /** 从 Shizuku 应用返回后刷新状态。 */
    fun refresh() {
        viewModelScope.launch { runCatching { hostShell.refresh() } }
    }

    fun requestPermission() = shizukuManager.requestPermission()

    fun openShizukuApp() = shizukuManager.openShizukuApp()
}
