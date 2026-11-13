package com.aharou.feature.settings.presentation.component

internal class AccountLoginWindowCoordinator {
    private var generation = 0L
    private var stopCurrent: (() -> Unit)? = null

    fun begin(stop: () -> Unit): Long {
        val previous = stopCurrent
        generation++
        stopCurrent = stop
        previous?.invoke()
        return generation
    }

    fun isCurrent(token: Long): Boolean = token == generation && stopCurrent != null

    fun close(token: Long, cleanup: () -> Unit) {
        if (!isCurrent(token)) return
        stopCurrent = null
        cleanup()
    }
}
