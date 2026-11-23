package com.aharou.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TawcrootRuntimeTest {
    @Test fun deviceGateRequiresAndroidTenAndArm64Process() {
        assertTrue(TawcrootRuntime.deviceSupported(29, true, listOf("arm64-v8a")))
        assertFalse(TawcrootRuntime.deviceSupported(28, true, listOf("arm64-v8a")))
        assertFalse(TawcrootRuntime.deviceSupported(35, false, listOf("arm64-v8a")))
        assertFalse(TawcrootRuntime.deviceSupported(35, true, listOf("x86_64")))
    }

    @Test fun argvUsesPinnedV6FlagsAndExplicitBindDestinations() {
        assertEquals(listOf("/native/libtawcroot.so", "-r", "/rootfs with space",
            "-b", "/dev:/dev", "-b", "/workspace with space:/root/workspace", "--"),
            TawcrootRuntime.prefix("/native/libtawcroot.so", "/rootfs with space",
                listOf("/dev", "/workspace with space:/root/workspace")))
    }

    @Test fun prootOnlyEnvironmentVariablesNeverReachGuestOrLauncher() {
        assertEquals(mapOf("HOME" to "/root", "TERM" to "xterm"), TawcrootRuntime.environment(mapOf(
            "PROOT_LOADER" to "/old", "PROOT_TMP_DIR" to "/tmp", "LD_LIBRARY_PATH" to "/android/lib",
            "LD_PRELOAD" to "/old.so", "HOME" to "/root", "TERM" to "xterm")))
    }

    @Test fun commandStartsFromBoundWorkspaceOrGuestHome() {
        assertEquals("cd /root/workspace || exit \$?; printf 'ok'",
            TawcrootRuntime.command("printf 'ok'", true))
        assertEquals("cd /root || exit \$?; printf 'ok'",
            TawcrootRuntime.command("printf 'ok'", false))
    }
}
