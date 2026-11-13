package com.aharou.feature.settings.presentation

import com.aharou.feature.settings.presentation.component.AccountLoginWindowCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLoginWindowCoordinatorTest {
    @Test fun oldWindowDelayedDestroyCannotCleanNewLogin() {
        val windows = AccountLoginWindowCoordinator()
        val events = mutableListOf<String>()
        val first = windows.begin { events += "stop-first" }
        val second = windows.begin { events += "stop-second" }
        windows.close(first) { events += "clear-first" }
        assertEquals(listOf("stop-first"), events)
        assertFalse(windows.isCurrent(first))
        assertTrue(windows.isCurrent(second))
        windows.close(second) { events += "clear-second" }
        assertEquals(listOf("stop-first", "clear-second"), events)
    }

    @Test fun reentrantDestroyWhileStoppingOldWindowDoesNotOwnCleanup() {
        val windows = AccountLoginWindowCoordinator()
        var first = 0L
        var cleaned = false
        first = windows.begin { windows.close(first) { cleaned = true } }
        val second = windows.begin { }
        assertFalse(cleaned)
        assertTrue(windows.isCurrent(second))
    }

    @Test fun currentCloseIsIdempotentAndLateReadyCannotBecomeCurrent() {
        val windows = AccountLoginWindowCoordinator()
        var cleaned = 0
        val token = windows.begin { }
        windows.close(token) { cleaned++ }
        windows.close(token) { cleaned++ }
        assertEquals(1, cleaned)
        assertFalse(windows.isCurrent(token))
    }
}
