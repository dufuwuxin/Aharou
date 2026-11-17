package com.aharou.core.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

fun Modifier.primarySettingsSwipe(enabled: Boolean, onSwipe: (Boolean) -> Unit): Modifier = composed {
    val callback by rememberUpdatedState(onSwipe)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    if (!enabled) this else pointerInput(threshold) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onHorizontalDrag = { change, amount -> distance += amount; change.consume() },
            onDragEnd = { if (kotlin.math.abs(distance) >= threshold) callback(distance < 0f) },
            onDragCancel = { distance = 0f },
        )
    }
}
