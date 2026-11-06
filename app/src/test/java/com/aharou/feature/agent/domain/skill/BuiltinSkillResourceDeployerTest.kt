package com.aharou.feature.agent.domain.skill

import com.aharou.feature.agent.domain.container.*
import com.aharou.feature.settings.data.repository.ExecutionMode
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BuiltinSkillResourceDeployerTest {
    private val source = mockk<BuiltinSkillSource>()
    private val profiles = mockk<ContainerProfileResolver>()
    private val configs = mockk<ActiveRemoteConnectionResolver>()
    private val connection = mockk<RemoteSshConnection>()
    private val deployer = BuiltinSkillResourceDeployer(source, profiles, configs, connection)
    private val profile = mockk<ContainerProfile> { every { mode } returns ExecutionMode.REMOTE_SSH; every { id } returns "remote" }
    @Test fun localSkillLoadingDoesNotCopyOrConnect() = runBlocking {
        every { profiles.forWorkspace("/w") } returns ContainerProfile.BUILTIN_ALPINE
        deployer.ensure("security-audit", "/w")
        verify(exactly = 0) { source.resourceFiles(any()) }
        coVerify(exactly = 0) { connection.connectTarget(any()) }
    }
    @Test fun resourcesAreSentToPinnedCurrentHostAndRetriedAfterInterruption() = runBlocking {
        val config = mockk<RemoteConnectionConfig>()
        val target = mockk<RemoteSshConnection.BootstrapTarget>()
        val files = mapOf("security-audit/scripts/audit.py" to "content".toByteArray())
        every { profiles.forWorkspace("/w") } returns profile
        every { source.resourceFiles("security-audit") } returns files
        coEvery { configs.configForProfile("remote") } returns config
        coEvery { connection.connectTarget(config) } returns target
        every { connection.isCurrentBootstrap(target) } returns true
        coEvery { connection.uploadBuiltinSkillResources(files, target) } throws java.io.IOException("interrupted")
        var failed = false
        try { deployer.ensure("security-audit", "/w") } catch (_: java.io.IOException) { failed = true }
        assertTrue(failed)
        coEvery { connection.uploadBuiltinSkillResources(files, target) } just Runs
        deployer.ensure("security-audit", "/w")
        coVerify(exactly = 2) { connection.uploadBuiltinSkillResources(files, target) }
        every { connection.isCurrentBootstrap(target) } returns false
        failed = false
        try { deployer.ensure("security-audit", "/w") } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
    }
}
