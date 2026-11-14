package com.aharou.feature.editor.domain

import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerWorkspaceKeys
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.workspace.domain.model.Workspace
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** UI drafts and document baselines follow the backend identity, not its display alias. */
class EditorWorkspaceIdentity @Inject constructor(
    private val settings: ContainerSettingsRepository,
    private val connection: RemoteSshConnection
) {
    data class Scope(val workspacePath: String, val identity: String)

    fun observe(workspaces: Flow<Workspace?>): Flow<Scope> = combine(
        workspaces,
        combine(settings.activeProfileIdFlow, settings.customProfilesFlow, settings.workspaceProfilesFlow) { active, profiles, overrides ->
            Triple(active, profiles, overrides)
        },
        connection.connectionState
    ) { workspace, profiles, _ ->
        val path = workspace?.path.orEmpty()
        val profile = resolve(profiles.first, profiles.second, profiles.third, path)
        val endpoint = if (profile.mode == ExecutionMode.REMOTE_SSH) connection.config?.let {
            listOf(it.host, it.port.toString(), it.username).joinToString("\u0000")
        }.orEmpty() else "local"
        Scope(path, key(path, profile.id, endpoint))
    }

    companion object {
        internal fun resolve(active: String, profiles: List<ContainerProfile>, overrides: Map<String, String>, path: String): ContainerProfile {
            fun configured(id: String): ContainerProfile? = profiles.firstOrNull { it.id == ContainerProfile.normalizeId(id) }
                ?: ContainerProfile.BUILTIN_UBUNTU.takeIf { it.id == ContainerProfile.normalizeId(id) }
            val default = configured(active) ?: profiles.firstOrNull() ?: ContainerProfile.BUILTIN_UBUNTU
            return ContainerWorkspaceKeys.overrideFor(overrides, path)?.let(::configured) ?: default
        }

        fun key(path: String, profile: String, endpoint: String): String = listOf(profile, endpoint, path).joinToString("\u0000")
    }
}
