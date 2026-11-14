package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.feature.agent.presentation.AIAgentViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SessionWorkspacePicker(viewModel: AIAgentViewModel, enabled: Boolean) {
    val sessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val path by viewModel.currentSessionWorkspace.collectAsStateWithLifecycle()
    val workspaces by viewModel.sessionWorkspaces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var open by remember(sessionId) { mutableStateOf(false) }
    var saving by remember(sessionId) { mutableStateOf(false) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    TextButton(enabled = enabled && sessionId != null && !saving, onClick = { open = true; failed = false },
        modifier = Modifier.testTag("session_workspace_select")) {
        Text(stringResource(R.string.session_workspace_select, path))
    }
    if (open) AlertDialog(onDismissRequest = { if (!saving) open = false },
        title = { Text(stringResource(R.string.session_workspace_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.session_workspace_hint))
                if (failed) Text(stringResource(R.string.session_workspace_failed))
                workspaces.filter { it.available }.forEach { workspace ->
                    TextButton(enabled = enabled && !saving, onClick = {
                        val id = sessionId ?: return@TextButton
                        saving = true
                        scope.launch {
                            try {
                                viewModel.bindSessionWorkspace(id, workspace.path)
                                open = false
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { failed = true }
                            finally { saving = false }
                        }
                    }) { Text(workspace.name) }
                }
            }
        }, confirmButton = { TextButton(enabled = !saving, onClick = { open = false }) {
            Text(stringResource(android.R.string.cancel))
        } })
}
