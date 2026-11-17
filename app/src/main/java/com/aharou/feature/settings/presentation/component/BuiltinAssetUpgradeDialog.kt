package com.aharou.feature.settings.presentation.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.BuiltinAssetUpgradeCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BuiltinAssetUpgradeDialog() {
    val context = LocalContext.current.applicationContext
    val coordinator = remember(context) { BuiltinAssetUpgradeCoordinator.initialize(context) }
    val state by coordinator.state.collectAsStateWithLifecycle()
    if (state.choice != BuiltinAssetUpgradeCoordinator.Choice.PENDING) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun decide(overwrite: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { coordinator.choose(overwrite) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = true
                FileLogger.w("BuiltinAssetUpgrade", "Cannot apply builtin asset choice", error)
            } finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { decide(false) },
        title = { Text(stringResource(R.string.builtin_asset_upgrade_title)) },
        text = {
            val message = stringResource(R.string.builtin_asset_upgrade_message)
            val missing = if (state.missingPreviousSkills) stringResource(R.string.builtin_asset_upgrade_missing_previous) else ""
            val error = if (failed) stringResource(R.string.builtin_asset_upgrade_error) else ""
            Text(listOf(message, missing, error).filter { it.isNotBlank() }.joinToString("\n\n"))
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { decide(true) }) {
                Text(stringResource(R.string.builtin_asset_upgrade_yes))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { decide(false) }) {
                Text(stringResource(R.string.builtin_asset_upgrade_no))
            }
        },
    )
}
