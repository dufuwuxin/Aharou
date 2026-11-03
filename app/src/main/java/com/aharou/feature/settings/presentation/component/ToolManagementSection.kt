package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
) {
    var scripts by rememberSaveable { mutableStateOf(false) }
    var createScript by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SegmentedTabs(
            selected = if (scripts) 1 else 0,
            labels = listOf(stringResource(R.string.settings_mcp), stringResource(R.string.script_tools_title)),
            onSelect = { scripts = it == 1 },
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
        if (scripts) {
            ScriptToolsSection(createRequested = createScript, onCreateHandled = { createScript = false })
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl)) {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.settings_tools_empty), Modifier.padding(Spacing.lg))
                }
                McpToolsSection(entries, statuses, onToggle, onDelete, onEdit)
            }
        }
    }
    if (createRequested) {
        AlertDialog(
            onDismissRequest = onCreateHandled,
            title = { Text(stringResource(R.string.tools_add_type)) },
            text = {
                Column {
                    TextButton(onClick = { scripts = false; onCreateHandled(); onCreateMcp() }) {
                        Text(stringResource(R.string.settings_add_mcp_server))
                    }
                    TextButton(onClick = { scripts = true; createScript = true; onCreateHandled() }) {
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
