package com.aharou.feature.plugin.presentation

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch

@Composable
fun HostPluginActions(
    enabled: Boolean,
    busy: Boolean,
    canCompile: Boolean,
    loaded: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onCompile: () -> Unit,
    onOpen: () -> Unit,
) {
    Text(stringResource(R.string.plugin_host_trust), style = MaterialTheme.typography.bodySmall)
    Row {
        Text(stringResource(R.string.plugin_host_enabled), modifier = Modifier.weight(1f))
        AppSwitch(checked = enabled, onCheckedChange = onEnabledChange, enabled = !busy)
    }
    Row {
        TextButton(onClick = onCompile, enabled = !busy && canCompile) {
            Text(stringResource(R.string.plugin_host_compile))
        }
        TextButton(onClick = onOpen, enabled = !busy && loaded) {
            Text(stringResource(R.string.plugin_host_open))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostPluginPanel(view: View?, output: String, onDismiss: () -> Unit) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().padding(Spacing.md).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.plugin_host_title), style = MaterialTheme.typography.titleMedium)
            if (view != null) {
                key(view) {
                    DisposableEffect(view) {
                        onDispose { (view.parent as? ViewGroup)?.removeView(view) }
                    }
                    AndroidView(factory = {
                        (view.parent as? ViewGroup)?.removeView(view)
                        view
                    }, modifier = Modifier.fillMaxWidth())
                }
            } else if (output.isEmpty()) {
                Text(stringResource(R.string.plugin_host_no_view))
            }
            if (output.isNotEmpty()) {
                Text(stringResource(R.string.plugin_host_compile_output), style = MaterialTheme.typography.titleSmall)
                Text(output, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
