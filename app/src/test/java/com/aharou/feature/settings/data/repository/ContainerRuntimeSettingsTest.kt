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
        val explicitProot = builtin.copy(id = "builtin-explicit-proot", runtimeBackend = ContainerRuntimeBackend.PROOT)
        val migrated = ContainerSettingsRepository.migrateLegacyRuntime(
            listOf(builtin, custom, explicitProot), legacyBackend = true,
            tawcrootCompatibleIds = setOf(builtin.id)
        )
        assertEquals(ContainerRuntimeBackend.TAWCROOT, migrated.first().runtimeBackend)
        assertEquals(ContainerRuntimeBackend.PROOT, migrated[1].runtimeBackend)
        assertEquals(ContainerRuntimeBackend.PROOT, migrated[2].runtimeBackend)
        assertEquals(listOf(builtin.id, custom.id, explicitProot.id), migrated.map { it.id })
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
