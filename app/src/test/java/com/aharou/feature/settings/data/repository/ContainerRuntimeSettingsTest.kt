package com.aharou.feature.settings.data.repository

import android.app.Application
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerRuntimeBackend
import com.aharou.feature.agent.domain.container.RootfsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ContainerRuntimeSettingsTest {
    @Test fun outputRedactionDefaultsOn() = runBlocking {
        val repository = ContainerSettingsRepository(RuntimeEnvironment.getApplication())
        assertTrue(repository.redactEnvOutputFlow.first())
        assertTrue(repository.redactEnvOutputEnabledSnapshot())
    }

    @Test fun legacyRuntimeMigratesOnlyVerifiedProfilesWithoutExplicitChoice() {
        val builtin = ContainerProfile.BUILTIN_UBUNTU
        val custom = ContainerProfile(
            id = "custom-ubuntu", name = "Ubuntu", rootfsSource = RootfsSource.LocalFile("file:///ubuntu.tar.gz"),
            shellPath = null, isBuiltin = false
        )
        val migrated = ContainerSettingsRepository.migrateLegacyRuntime(
            listOf(builtin, custom), legacyBackend = true,
            tawcrootCompatibleIds = setOf(builtin.id)
        )
        assertEquals(ContainerRuntimeBackend.TAWCROOT, migrated.first().runtimeBackend)
        assertEquals(ContainerRuntimeBackend.PROOT, migrated[1].runtimeBackend)
        assertEquals(listOf(builtin.id, custom.id), migrated.map { it.id })

        val explicitProot = ContainerSettingsRepository.migrateLegacyRuntime(
            listOf(builtin.copy(runtimeBackend = ContainerRuntimeBackend.PROOT)),
            legacyBackend = true,
            tawcrootCompatibleIds = setOf(builtin.id)
        ).single()
        assertEquals(ContainerRuntimeBackend.PROOT, explicitProot.runtimeBackend)
    }

    @Test fun runtimeBackendIsStoredPerProfile() = runBlocking {
        val repository = ContainerSettingsRepository(RuntimeEnvironment.getApplication())
        val first = ContainerProfile(
            id = "test-runtime-first", name = "First", rootfsSource = RootfsSource.LocalFile("file:///first.tar.gz"),
            shellPath = null, isBuiltin = false, runtimeBackend = ContainerRuntimeBackend.PROOT
        )
        val second = ContainerProfile(
            id = "test-runtime-second", name = "Second", rootfsSource = RootfsSource.LocalFile("file:///second.tar.gz"),
            shellPath = null, isBuiltin = false, runtimeBackend = ContainerRuntimeBackend.PROOT
        )
        try {
            repository.upsertCustomProfile(first)
            repository.upsertCustomProfile(second)
            repository.upsertCustomProfile(first.copy(runtimeBackend = ContainerRuntimeBackend.TAWCROOT))
            val profiles = repository.customProfilesFlow.first().associateBy { it.id }
            assertEquals(ContainerRuntimeBackend.TAWCROOT, profiles.getValue(first.id).runtimeBackend)
            assertEquals(ContainerRuntimeBackend.PROOT, profiles.getValue(second.id).runtimeBackend)
        } finally {
            repository.deleteCustomProfile(first.id)
            repository.deleteCustomProfile(second.id)
        }
    }

    @Test fun absentLegacyRuntimeSettingKeepsBuiltInProfileDefaultOnProot() {
        val migrated = ContainerSettingsRepository.migrateLegacyRuntime(
            listOf(ContainerProfile.BUILTIN_UBUNTU), legacyBackend = null
        )
        assertEquals(null, migrated.single().runtimeBackend)
    }

    @Test fun legacyDisabledRuntimeBecomesExplicitProotWhenMigrated() {
        val migrated = ContainerSettingsRepository.migrateLegacyRuntime(
            listOf(ContainerProfile.BUILTIN_UBUNTU), legacyBackend = false
        )
        assertEquals(ContainerRuntimeBackend.PROOT, migrated.single().runtimeBackend)
    }

    @Test fun legacyPrivacyPreferencesPreserveExplicitVaultChoiceUnlessContainerProtectionWasExplicitlyOn() {
        assertTrue(ContainerSettingsRepository.mergeLegacyPrivacyRedaction(true, false, false))
        assertTrue(ContainerSettingsRepository.mergeLegacyPrivacyRedaction(false, true, true))
        assertFalse(ContainerSettingsRepository.mergeLegacyPrivacyRedaction(false, false, true))
        assertFalse(ContainerSettingsRepository.mergeLegacyPrivacyRedaction(null, false, true))
        assertTrue(ContainerSettingsRepository.mergeLegacyPrivacyRedaction(null, null, false))
    }

    @Test fun outputRedactionSettingUpdatesItsSynchronousExecutionSnapshot() = runBlocking {
        val repository = ContainerSettingsRepository(RuntimeEnvironment.getApplication())
        repository.setRedactEnvOutput(false)
        assertFalse(repository.redactEnvOutputEnabledSnapshot())
        repository.setRedactEnvOutput(true)
        assertTrue(repository.redactEnvOutputEnabledSnapshot())
    }
}
