package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope

internal suspend fun PointerInputScope.detectProjectMapGestures(onGesture: (Offset, Offset, Float, Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var claimed = false
        var accumulated = Offset.Zero
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val multi = event.changes.count { it.pressed } >= 2
            val pan = event.calculatePan()
            accumulated += pan
            if (multi || claimed || accumulated.getDistance() > viewConfiguration.touchSlop) {
                claimed = true
                onGesture(event.calculateCentroid(useCurrent = false), pan, event.calculateZoom(), 0f)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}
