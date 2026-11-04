package com.aharou.feature.agent.domain.shell

import com.aharou.feature.agent.domain.shizuku.ShizukuManager
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

enum class HostShellMode {
    SHIZUKU,
    UNAVAILABLE
}

data class ShellCommandResult(val output: String, val exitCode: Int)

@Singleton
class HostShellManager @Inject constructor(
    private val shizukuManager: ShizukuManager,
) {
    companion object {
        fun resolve(shizukuState: ShizukuState): HostShellMode =
            if (shizukuState == ShizukuState.READY) HostShellMode.SHIZUKU else HostShellMode.UNAVAILABLE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mode: StateFlow<HostShellMode> = shizukuManager.state.map(::resolve)
        .stateIn(scope, SharingStarted.Eagerly, HostShellMode.UNAVAILABLE)
    val shizukuState: StateFlow<ShizukuState> = shizukuManager.state

    suspend fun refresh() {
        shizukuManager.refreshState()
    }

    suspend fun ensureMode(): HostShellMode = resolve(shizukuManager.state.value)

    suspend fun run(command: String, timeoutMs: Long): ShellCommandResult {
        check(shizukuManager.state.value == ShizukuState.READY) {
            "宿主命令通道不可用（Shizuku: ${shizukuManager.state.value}）：请安装并授权 Shizuku"
        }
        val result = shizukuManager.runCommand(command, timeoutMs)
        return ShellCommandResult(result.output, result.exitCode)
    }
}
