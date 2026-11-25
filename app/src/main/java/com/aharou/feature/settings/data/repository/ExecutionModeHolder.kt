package com.aharou.feature.settings.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 旧执行模式仓储的同步缓存。远程 SSH 能力退役后，旧调用方写入的 REMOTE_SSH 一律不再生效。
 */
@Singleton
class ExecutionModeHolder @Inject constructor() {
    private val _mode = MutableStateFlow(ExecutionMode.LOCAL_PROOT)
    val mode: StateFlow<ExecutionMode> = _mode.asStateFlow()

    fun setMode(mode: ExecutionMode) {
        _mode.value = ExecutionMode.LOCAL_PROOT
    }

    fun currentMode(): ExecutionMode = _mode.value
}
