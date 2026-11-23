package com.aharou.feature.settings.data.repository

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    @Test fun experimentalRuntimeDefaultsOffAndOutputRedactionDefaultsOn() = runBlocking {
        val repository = ContainerSettingsRepository(RuntimeEnvironment.getApplication())
        assertFalse(repository.useTawcrootFlow.first())
        assertTrue(repository.redactEnvOutputFlow.first())
        assertTrue(repository.redactEnvOutputEnabledSnapshot())
    }

    @Test fun outputRedactionSettingUpdatesItsSynchronousExecutionSnapshot() = runBlocking {
        val repository = ContainerSettingsRepository(RuntimeEnvironment.getApplication())
        repository.setRedactEnvOutput(false)
        assertFalse(repository.redactEnvOutputEnabledSnapshot())
        repository.setRedactEnvOutput(true)
        assertTrue(repository.redactEnvOutputEnabledSnapshot())
    }
}
