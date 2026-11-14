package com.aharou.feature.agent.domain.container

import com.aharou.core.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal fun launchContainerCommandWatchdog(
    scope: CoroutineScope,
    timeoutMs: Long,
    maximumMs: Long,
    timedOut: AtomicBoolean,
    promptsInFlight: () -> Int,
    sanitizedCommand: String,
    terminate: () -> Unit,
): Job = scope.launch {
    var remaining = timeoutMs
    var waited = 0L
    while (true) {
        delay(remaining)
        waited += remaining
        val prompts = promptsInFlight()
        if (prompts > 0 && waited < maximumMs) {
            FileLogger.i("LinuxContainerEngine", "凭据弹窗在途，watchdog 延后: $sanitizedCommand")
            remaining = minOf(60_000L, maximumMs - waited)
            continue
        }
        timedOut.set(true)
        FileLogger.w("LinuxContainerEngine", "命令输出等待超过 ${timeoutMs}ms（累计 ${waited}ms）: $sanitizedCommand")
        terminate()
        return@launch
    }
}
