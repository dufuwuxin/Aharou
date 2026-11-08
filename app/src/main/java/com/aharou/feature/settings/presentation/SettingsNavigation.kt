package com.aharou.feature.settings.presentation

internal fun <T> settingsNavigationPath(path: List<T>, target: T): List<T> {
    val previous = path.indexOf(target)
    return if (previous >= 0) path.take(previous + 1) else path + target
}
