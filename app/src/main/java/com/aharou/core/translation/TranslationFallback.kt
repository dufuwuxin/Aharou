package com.aharou.core.translation

import kotlinx.coroutines.CancellationException

internal suspend fun translationOrOriginal(original: String, translate: suspend () -> String): String =
    try {
        translate()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        original
    } catch (_: LinkageError) {
        // A failed optional translator must not terminate the page that owns its coroutine.
        original
    }
