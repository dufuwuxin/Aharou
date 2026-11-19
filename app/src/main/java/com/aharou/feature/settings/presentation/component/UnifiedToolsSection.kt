package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
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
    val excludedNames = definitions.mapTo(hashSetOf()) { it.toolName }
    var tab by rememberSaveable { mutableStateOf("custom") }
    LaunchedEffect(state.editor) { if (state.editor != null) tab = "custom" }
    LaunchedEffect(createScript, createTask) {
        if (createScript) tab = "scripts"
        if (createTask) tab = "tasks"
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf("custom" to R.string.tools_tab_custom, "scripts" to R.string.tools_tab_scripts,
                "builtin" to R.string.tools_tab_builtin, "mcp" to R.string.settings_mcp,
                "tasks" to R.string.settings_scheduled_tasks, "manage" to R.string.tools_management_actions).forEach { (key, label) ->
                FilterChip(selected = tab == key, onClick = { tab = key },
                    label = { Text(stringResource(label)) }, modifier = Modifier.testTag("tools_tab_$key"))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            when (tab) {
                "custom", "builtin", "mcp" -> ToolsSection(
                    state = state, viewModel = viewModel, embedded = true,
                    sources = setOf(when (tab) { "builtin" -> ToolSource.BUILTIN; "mcp" -> ToolSource.MCP; else -> ToolSource.CUSTOM }),
                    excludedNames = if (tab == "custom") emptySet() else excludedNames,
                    onRun = onRun, onRunDraft = onRunDraft,
                )
                "scripts" -> ScriptToolsSection(
                    createRequested = createScript, onCreateHandled = onScriptHandled,
                    viewModel = scriptViewModel, embedded = true,
                    onEditSettings = { name -> if (state.entries.any { it.name == name }) { viewModel.openEditor(name); tab = "custom" } },
                )
                "tasks" -> ScheduledTasksSection(
                    createRequested = createTask, onCreateHandled = onTaskHandled, embedded = true, expanded = true,
                )
                "manage" -> {
                    SettingsGroup {
                        SettingsRow(title = stringResource(R.string.tools_history_title), onClick = onOpenRunner)
                        SettingsDivider()
                        SettingsRow(title = stringResource(R.string.settings_mcp), onClick = onManageMcp)
                        SettingsDivider()
                        SettingsRow(title = stringResource(R.string.editor_settings),
                            subtitle = stringResource(R.string.toolbox_editor_hint), onClick = onEditorSettings)
                    }
                    SettingsGroupHeader(text = stringResource(R.string.settings_permissions))
                    permissionContent()
                }
            }
        }
    }
}
