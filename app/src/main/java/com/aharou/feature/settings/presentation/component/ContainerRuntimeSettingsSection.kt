package com.aharou.feature.settings.presentation.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerRuntimeBackend
import com.aharou.feature.agent.domain.container.TawcrootRuntime
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.workspace.domain.model.RemoteConnection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ActiveContainerRuntimeBackendSection(
    profile: ContainerProfile?,
    onChange: (ContainerProfile) -> Unit,
) {
    val activeLocal = profile?.takeIf { it.mode == ExecutionMode.LOCAL_PROOT } ?: return
    SettingsGroup {
        ContainerRuntimeBackendRow(
            profile = activeLocal,
            mode = ExecutionMode.LOCAL_PROOT,
            backend = activeLocal.runtimeBackend ?: ContainerRuntimeBackend.PROOT,
            onBackendChange = { onChange(activeLocal.copy(runtimeBackend = it)) },
        )
    }
}

@Composable
internal fun ContainerRuntimeBackendRow(
    profile: ContainerProfile?,
    mode: ExecutionMode,
    backend: ContainerRuntimeBackend,
    onBackendChange: (ContainerRuntimeBackend) -> Unit
) {
    val context = LocalContext.current
    var available by remember(profile?.id, profile?.rootfsSource, mode) { mutableStateOf(false) }
    LaunchedEffect(context, profile?.id, profile?.rootfsSource, mode) {
        available = withContext(Dispatchers.IO) {
            profile != null && TawcrootRuntime.availableForProfile(
                context,
                profile.copy(mode = mode),
                if (profile.isBuiltin) File(context.filesDir, "rootfs_ubuntu") else File(context.filesDir, "rootfs_${profile.id}")
            )
        }
    }
    SettingsRow(
        title = stringResource(R.string.container_runtime_backend),
        subtitle = stringResource(
            if (available || backend == ContainerRuntimeBackend.TAWCROOT) {
                R.string.container_runtime_backend_tawcroot_desc
            } else R.string.container_runtime_backend_tawcroot_unavailable
        ),
        trailing = {
            AppSwitch(
                checked = backend == ContainerRuntimeBackend.TAWCROOT,
                enabled = available || backend == ContainerRuntimeBackend.TAWCROOT,
                modifier = Modifier.testTag("container_runtime_backend_switch"),
                onCheckedChange = { enabled ->
                    onBackendChange(if (enabled) ContainerRuntimeBackend.TAWCROOT else ContainerRuntimeBackend.PROOT)
                }
            )
        }
    )
}

internal fun containerProfileSubtitle(context: Context, profile: ContainerProfile, connections: List<RemoteConnection>): String {
    if (profile.mode == ExecutionMode.REMOTE_SSH) {
        val ssh = profile.rootfsSource as? com.aharou.feature.agent.domain.container.RootfsSource.RemoteSsh
        val connectionName = ssh?.connectionId?.let { id -> connections.firstOrNull { it.id == id }?.name }
        return connectionName ?: context.getString(R.string.container_channel_deleted)
    }
    val shell = profile.shellPath?.ifBlank { null } ?: "/bin/sh"
    val backend = context.getString(
        if (TawcrootRuntime.enabled(profile)) R.string.container_runtime_backend_tawcroot
        else R.string.container_runtime_backend_proot
    )
    return context.getString(R.string.container_profile_runtime_subtitle, backend, shell)
}

@Composable
internal fun PrivacyRedactionSettingsRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingsRow(
        title = stringResource(R.string.settings_container_env_redaction_title),
        subtitle = stringResource(R.string.settings_container_env_redaction_desc),
        trailing = { AppSwitch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}
