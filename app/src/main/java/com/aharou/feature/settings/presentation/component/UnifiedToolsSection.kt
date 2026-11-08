package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.feature.settings.presentation.ToolSource
import com.aharou.feature.settings.presentation.ToolUiEntry
import com.aharou.feature.settings.presentation.ToolsUiState
import com.aharou.feature.settings.presentation.ToolsViewModel

@Composable
internal fun UnifiedToolsSection(
    state: ToolsUiState,
    viewModel: ToolsViewModel,
    scriptViewModel: ScriptToolsViewModel,
    createScript: Boolean,
    onScriptHandled: () -> Unit,
    createTask: Boolean,
    onTaskHandled: () -> Unit,
    onRun: (ToolUiEntry) -> Unit,
    onRunDraft: (com.aharou.feature.agent.domain.tool.custom.CustomToolDefinition) -> Unit,
    onOpenRunner: () -> Unit,
    onManageMcp: () -> Unit,
    onEditorSettings: () -> Unit,
    permissionContent: @Composable () -> Unit,
) {
    val definitions by scriptViewModel.definitions.collectAsStateWithLifecycle()
    val excludedNames = definitions.map { it.toolName }.toSet()
    var dynamicExpanded by rememberSaveable { mutableStateOf(false) }
    var tasksExpanded by rememberSaveable { mutableStateOf(false) }
    var managementExpanded by rememberSaveable { mutableStateOf(false) }
    var permissionsExpanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsGroup {
            SettingsRow(title = stringResource(R.string.editor_settings),
                subtitle = stringResource(R.string.toolbox_editor_hint), onClick = onEditorSettings)
        }
        ToolsSection(
            state = state, viewModel = viewModel, embedded = true,
            sources = setOf(ToolSource.CUSTOM), onRun = onRun, onRunDraft = onRunDraft,
        )
        ScriptToolsSection(
            createRequested = createScript, onCreateHandled = onScriptHandled,
            viewModel = scriptViewModel, embedded = true,
            onEditSettings = { name ->
                if (state.entries.any { it.name == name }) viewModel.openEditor(name)
            },
        )
        CollapsibleGroupHeader(
            text = stringResource(R.string.tools_dynamic_group), expanded = dynamicExpanded,
            onToggle = { dynamicExpanded = !dynamicExpanded },
        )
        if (dynamicExpanded) {
            // The custom list owns the shared editor and one-shot feedback.
            ToolsSection(
                state = state.copy(editor = null, message = null), viewModel = viewModel, embedded = true,
                sources = setOf(ToolSource.BUILTIN, ToolSource.MCP), excludedNames = excludedNames,
            )
        }
        CollapsibleGroupHeader(
            text = stringResource(R.string.settings_scheduled_tasks), expanded = tasksExpanded,
            onToggle = { tasksExpanded = !tasksExpanded },
        )
        ScheduledTasksSection(
            createRequested = createTask, onCreateHandled = onTaskHandled,
            embedded = true, expanded = tasksExpanded,
        )
        CollapsibleGroupHeader(
            text = stringResource(R.string.tools_management_actions), expanded = managementExpanded,
            onToggle = { managementExpanded = !managementExpanded },
        )
        if (managementExpanded) {
            SettingsGroup {
                SettingsRow(title = stringResource(R.string.tools_history_title), onClick = onOpenRunner)
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.settings_mcp), onClick = onManageMcp)
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_permissions),
                    onClick = { permissionsExpanded = !permissionsExpanded },
                )
            }
            if (permissionsExpanded) permissionContent()
        }
    }
}
