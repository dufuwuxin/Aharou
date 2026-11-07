package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.feature.settings.presentation.ToolsViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.SegmentedTabs
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.agent.domain.mcp.McpServerStatus
import com.aharou.feature.agent.domain.mcp.McpScope

@Composable
internal fun ToolManagementSection(
    entries: List<McpServerEntry>,
    statuses: List<McpServerStatus>,
    reloading: Boolean,
    onReload: () -> Unit,
    onBrowseDirectory: () -> Unit,
    onImportJson: () -> Unit,
    onExportJson: () -> Unit,
    createRequested: Boolean,
    onCreateHandled: () -> Unit,
    onCreateMcp: () -> Unit,
    onToggle: (String, Boolean, McpScope) -> Unit,
    onDelete: (String, McpScope) -> Unit,
    onEdit: (McpServerEntry) -> Unit,
    permissionContent: @Composable () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val customViewModel: ToolsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val customState by customViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(selected) { if (selected == 3) customViewModel.refresh() }
    var createScript by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SegmentedTabs(
            selected = selected,
            labels = listOf(stringResource(R.string.toolbox_title), stringResource(R.string.settings_mcp), stringResource(R.string.script_tools_title), stringResource(R.string.tools_group_custom), stringResource(R.string.settings_scheduled_tasks), stringResource(R.string.settings_permissions)),
            onSelect = { selected = it },
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
        Box(Modifier.weight(1f)) {
            if (selected == 0) {
                ToolboxSection()
            } else if (selected == 1) {
                Column {
                    TextButton(onClick = onReload, enabled = !reloading, modifier = Modifier.padding(horizontal = Spacing.lg)) {
                        Text(stringResource(R.string.settings_reconnect))
                    }
                McpSection(
                    entries = entries, statuses = statuses, reloading = reloading, onReload = onReload,
                    onToggle = onToggle, onDelete = onDelete, onEdit = onEdit,
                    onBrowseDirectory = onBrowseDirectory, onImportJson = onImportJson, onExportJson = onExportJson
                )
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (selected) {
                        2 -> ScriptToolsSection(createRequested = createScript, onCreateHandled = { createScript = false }, embedded = true)
                        3 -> ToolsSection(state = customState, viewModel = customViewModel, embedded = true)
                        4 -> ScheduledTasksSection()
                        else -> {
                            SettingsGroupHeader(text = stringResource(R.string.settings_permissions))
                            permissionContent()
                        }
                    }
                }
            }
        }
    }
    if (createRequested) {
        AlertDialog(
            onDismissRequest = onCreateHandled,
            title = { Text(stringResource(R.string.tools_add_type)) },
            text = {
                Column {
                    TextButton(onClick = { selected = 1; onCreateHandled(); onCreateMcp() }) {
                        Text(stringResource(R.string.settings_add_mcp_server))
                    }
                    TextButton(onClick = { selected = 3; customViewModel.startCreate(); onCreateHandled() }) {
                        Text(stringResource(R.string.tools_add))
                    }
                    TextButton(onClick = { selected = 2; createScript = true; onCreateHandled() }) {
                        Text(stringResource(R.string.script_tools_add))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onCreateHandled) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
