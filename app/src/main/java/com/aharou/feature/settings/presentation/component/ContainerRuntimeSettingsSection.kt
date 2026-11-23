package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.agent.domain.container.TawcrootRuntime
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ContainerRuntimeSettingsEntryPoint {
    fun containerSettingsRepository(): ContainerSettingsRepository
}

@Composable
internal fun ContainerRuntimeSettingsSection() {
    val context = LocalContext.current
    val repository = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, ContainerRuntimeSettingsEntryPoint::class.java)
            .containerSettingsRepository()
    }
    val redact by repository.redactEnvOutputFlow.collectAsState(initial = true)
    val tawcroot by repository.useTawcrootFlow.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        available = withContext(Dispatchers.IO) { TawcrootRuntime.available(context) }
    }
    Column {
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_container_env_redaction_title),
                subtitle = stringResource(R.string.settings_container_env_redaction_desc),
                trailing = {
                    AppSwitch(checked = redact, onCheckedChange = { enabled ->
                        scope.launch { repository.setRedactEnvOutput(enabled) }
                    })
                }
            )
        }
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.tawcroot_title),
                subtitle = stringResource(if (available || tawcroot) R.string.tawcroot_description else R.string.tawcroot_unavailable),
                trailing = {
                    AppSwitch(checked = tawcroot, enabled = available || tawcroot, onCheckedChange = { enabled ->
                        scope.launch {
                            failure = null
                            try {
                                withContext(Dispatchers.IO) {
                                    if (enabled) TawcrootRuntime.requireAvailable(context)
                                    repository.setUseTawcroot(enabled)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                failure = error.message.orEmpty()
                            }
                        }
                    })
                }
            )
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
