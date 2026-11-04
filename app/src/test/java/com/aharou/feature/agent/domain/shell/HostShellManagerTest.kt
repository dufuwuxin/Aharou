package com.aharou.feature.agent.domain.shell

import com.aharou.feature.agent.domain.shizuku.ShizukuState
import org.junit.Assert.assertEquals
import org.junit.Test

class HostShellManagerTest {
    @Test
    fun readyShizukuIsTheOnlyAvailableChannel() {
        assertEquals(HostShellMode.SHIZUKU, HostShellManager.resolve(ShizukuState.READY))
    }

    @Test
    fun unavailableUntilShizukuIsInstalledRunningAndAuthorized() {
        listOf(ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING, ShizukuState.PERMISSION_DENIED).forEach {
            assertEquals(HostShellMode.UNAVAILABLE, HostShellManager.resolve(it))
        }
    }
}
