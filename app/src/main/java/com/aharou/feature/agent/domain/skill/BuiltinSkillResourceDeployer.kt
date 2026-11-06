package com.aharou.feature.agent.domain.skill

import com.aharou.feature.agent.domain.container.ActiveRemoteConnectionResolver
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.settings.data.repository.ExecutionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuiltinSkillResourceDeployer @Inject constructor(
    private val source: BuiltinSkillSource,
    private val profiles: ContainerProfileResolver,
    private val configs: ActiveRemoteConnectionResolver,
    private val connection: RemoteSshConnection
) {
    /** An interrupted sync never reports readiness; the next load retries and compares actual bytes. */
    suspend fun ensure(name: String, workspace: String?) = withContext(Dispatchers.IO) {
        val profile = profiles.forWorkspace(workspace)
        if (profile.mode != ExecutionMode.REMOTE_SSH) return@withContext
        val files = source.resourceFiles(name)
        check(files.isNotEmpty()) { "BUILTIN_SKILL_RESOURCES_MISSING" }
        val config = configs.configForProfile(profile.id) ?: error("SSH_CONFIGURATION_MISSING")
        val target = connection.connectTarget(config)
        connection.uploadBuiltinSkillResources(files, target)
        check(connection.isCurrentBootstrap(target) && profiles.forWorkspace(workspace) == profile) { "SSH_CONNECTION_CHANGED" }
    }
}
