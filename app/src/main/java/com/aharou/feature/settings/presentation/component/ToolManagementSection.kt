package com.aharou.feature.settings.presentation.component

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

@Composable
internal fun ToolManagementSection(
    entries: List<McpServerEntry>,
    statuses: List<McpServerStatus>,
    createRequested: Boolean,
    onCreateHandled: () -> Unit,
    onCreateMcp: () -> Unit,
    onToggle: (McpServerEntry, Boolean) -> Unit,
    onDelete: (McpServerEntry) -> Unit,
    onEdit: (McpServerEntry) -> Unit,
    permissionContent: @Composable () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val customViewModel: ToolsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val customState by customViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(selected) { if (selected == 2) customViewModel.refresh() }
    var createScript by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SegmentedTabs(
            selected = selected,
            labels = listOf(stringResource(R.string.settings_mcp), stringResource(R.string.script_tools_title), stringResource(R.string.tools_group_custom)),
            onSelect = { selected = it },
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
        if (selected == 2) {
            ToolsSection(state = customState, viewModel = customViewModel, embedded = true)
        } else if (selected == 1) {
            ScriptToolsSection(createRequested = createScript, onCreateHandled = { createScript = false }, embedded = true)
        } else {
            Column(Modifier.padding(bottom = Spacing.xl)) {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.settings_tools_empty), Modifier.padding(Spacing.lg))
                }
                McpToolsSection(entries, statuses, onToggle, onDelete, onEdit)
            }
        }
        ScheduledTasksSection()
        SettingsGroupHeader(text = stringResource(R.string.settings_permissions))
        permissionContent()
    }
    if (createRequested) {
        AlertDialog(
            onDismissRequest = onCreateHandled,
            title = { Text(stringResource(R.string.tools_add_type)) },
            text = {
                Column {
                    TextButton(onClick = { selected = 0; onCreateHandled(); onCreateMcp() }) {
                        Text(stringResource(R.string.settings_add_mcp_server))
                    }
                    TextButton(onClick = { selected = 2; customViewModel.startCreate(); onCreateHandled() }) {
                        Text(stringResource(R.string.tools_add))
                    }
                    TextButton(onClick = { selected = 1; createScript = true; onCreateHandled() }) {
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
