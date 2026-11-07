package com.aharou.feature.agent.domain.container

import com.aharou.feature.settings.data.repository.ExecutionMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContainerProfileMigrationTest {
    @Test fun legacyIdentityAndAssetAreNormalizedTogether() {
        val legacy = ContainerProfile.BUILTIN_UBUNTU.copy(
            id = ContainerProfile.LEGACY_BUILTIN_ID,
            rootfsSource = RootfsSource.Asset("alpine-rootfs.bin"),
            shellPath = "/bin/ash", env = mapOf("USER_CHOICE" to "kept"),
            extraArgs = listOf("--kill-on-exit"), extraBindings = listOf("/host:/guest")
        )
        val migrated = legacy.normalizeBuiltin()
        assertEquals("builtin-ubuntu", migrated.id)
        assertEquals(RootfsSource.Asset("container/arm/ubuntu-rootfs.bin"), migrated.rootfsSource)
        assertEquals(null, migrated.shellPath)
        assertEquals(legacy.env, migrated.env)
        assertEquals(legacy.extraArgs, migrated.extraArgs)
        assertEquals(legacy.extraBindings, migrated.extraBindings)
        assertEquals(migrated, migrated.normalizeBuiltin())
        assertEquals(migrated, Json.decodeFromString(ContainerProfile.serializer(), Json.encodeToString(ContainerProfile.serializer(), migrated)))
    }

    @Test fun activeDefaultAndWorkspaceLegacyIdsUseSameMapping() {
        assertEquals(ContainerProfile.BUILTIN_ID, ContainerProfile.normalizeId("builtin-alpine"))
        assertEquals(ContainerProfile.BUILTIN_ID, ContainerProfile.normalizeId("builtin-ubuntu"))
        assertEquals("custom", ContainerProfile.normalizeId("custom"))
    }

    @Test fun userImportedAlpineAndRemoteServerAreNotDeletedOrRewritten() {
        val local = ContainerProfile(id = "custom", name = "Alpine import", rootfsSource = RootfsSource.LocalFile("file:///keep.tar.gz"),
            shellPath = null, isBuiltin = false)
        val remote = local.copy(id = "ssh", rootfsSource = RootfsSource.RemoteSsh("conn", "/home/u"), mode = ExecutionMode.REMOTE_SSH)
        assertEquals(local, local.normalizeBuiltin())
        assertEquals(remote, remote.normalizeBuiltin())
        assertFalse(remote.normalizeBuiltin().isBuiltin)
    }
}
