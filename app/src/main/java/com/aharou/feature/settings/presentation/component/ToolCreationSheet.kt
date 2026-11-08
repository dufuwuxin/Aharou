package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCreationSheet(
    onDismiss: () -> Unit,
    onCreateCommand: () -> Unit,
    onCreateScript: () -> Unit,
    onCreateMcp: () -> Unit,
    onCreateTask: () -> Unit,
    onImportTool: () -> Unit,
    onImportMcp: () -> Unit,
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg)) {
            SettingsGroupHeader(text = stringResource(R.string.tools_add_type))
            SettingsGroup {
                SettingsRow(title = stringResource(R.string.tools_create_command), onClick = onCreateCommand)
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.script_tools_add), onClick = onCreateScript)
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.settings_add_mcp_server), onClick = onCreateMcp)
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.scheduled_tasks_add), onClick = onCreateTask)
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.tools_import_command_json), onClick = onImportTool)
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.tools_import_mcp_json), onClick = onImportMcp)
            }
        }
    }
}
