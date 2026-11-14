package com.aharou.feature.terminal.domain

internal fun terminalReceiptExitCode(output: String, token: String?): Int? {
    if (token.isNullOrBlank()) return null
    val prefix = "[aharou-exit:$token:"
    val tail = output
    val at = tail.lastIndexOf(prefix)
    if (at < maxOf(0, output.length - 1000) || at > 0 && tail[at - 1] != '\n' && tail[at - 1] != '\r') return null
    val end = tail.indexOf(']', at + prefix.length)
    if (end < 0) return null
    val code = tail.substring(at + prefix.length, end).toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    if (end + 1 < tail.length && tail[end + 1] != '\r' && tail[end + 1] != '\n') return null
    return code
}
