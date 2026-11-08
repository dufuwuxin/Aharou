package com.aharou.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import com.aharou.feature.editor.presentation.EditorSettingsScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.aharou.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.agent.domain.mcp.McpServerStatus
import com.aharou.feature.agent.domain.tool.custom.CustomToolDefinition
import com.aharou.feature.settings.presentation.ToolboxMessage
import com.aharou.feature.settings.presentation.ToolboxViewModel
import com.aharou.feature.settings.presentation.ToolsViewModel
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
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
    onToggle: (String, Boolean, com.aharou.feature.agent.domain.mcp.McpScope) -> Unit,
    onDelete: (String, com.aharou.feature.agent.domain.mcp.McpScope) -> Unit,
    onEdit: (McpServerEntry) -> Unit,
    permissionContent: @Composable () -> Unit,
) {
    var editorSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = editorSettings) { editorSettings = false }
    if (editorSettings) {
        EditorSettingsScreen(onBack = { editorSettings = false })
        return
    }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var createScript by rememberSaveable { mutableStateOf(false) }
    var createTask by rememberSaveable { mutableStateOf(false) }
    var importTool by rememberSaveable { mutableStateOf(false) }
    var showRunner by rememberSaveable { mutableStateOf(false) }
    var selectedDraft by rememberSaveable { mutableStateOf(false) }
    var showMcp by rememberSaveable { mutableStateOf(false) }
    val customViewModel: ToolsViewModel = hiltViewModel()
    val scriptViewModel: ScriptToolsViewModel = hiltViewModel()
    val toolboxViewModel: ToolboxViewModel = hiltViewModel()
    val customState by customViewModel.state.collectAsStateWithLifecycle()
    val toolboxState by toolboxViewModel.state.collectAsStateWithLifecycle()
    val definitions by scriptViewModel.definitions.collectAsStateWithLifecycle()
    LaunchedEffect(createRequested) {
        if (createRequested) {
            showCreate = true
            onCreateHandled()
        }
    }
    LaunchedEffect(entries, statuses, definitions) { customViewModel.refresh() }
    LaunchedEffect(customState.entries) { toolboxViewModel.invalidateSelection() }
    LaunchedEffect(toolboxState.message) {
        if (toolboxState.message == ToolboxMessage.SAVED) customViewModel.refresh()
    }
    UnifiedToolsSection(
        state = customState, viewModel = customViewModel, scriptViewModel = scriptViewModel,
        createScript = createScript, onScriptHandled = { createScript = false },
        createTask = createTask, onTaskHandled = { createTask = false },
        onRun = { entry ->
            selectedDraft = false
            toolboxViewModel.select(CustomToolDefinition(
                name = entry.name, description = entry.description, command = entry.command,
                parameters = Json.decodeFromString(entry.parametersJson), timeoutSeconds = entry.timeoutSeconds,
                injectionMode = entry.injectionMode, keywords = entry.keywords,
            ))
            showRunner = true
        },
        onRunDraft = { draft -> selectedDraft = true; toolboxViewModel.select(draft); showRunner = true },
        onOpenRunner = { toolboxViewModel.invalidateSelection(); showRunner = true },
        onManageMcp = { showMcp = true },
        onEditorSettings = { editorSettings = true },
        permissionContent = permissionContent,
    )
    if (showCreate) {
        ToolCreationSheet(
            onDismiss = { showCreate = false },
            onCreateCommand = { showCreate = false; customViewModel.startCreate() },
            onCreateScript = { showCreate = false; createScript = true },
            onCreateMcp = { showCreate = false; onCreateMcp() },
            onCreateTask = { showCreate = false; createTask = true },
            onImportTool = { showCreate = false; showRunner = false; importTool = true },
            onImportMcp = { showCreate = false; onImportJson() },
        )
    }
    ToolImportSheet(visible = importTool, viewModel = toolboxViewModel, onDismiss = { importTool = false })
    if (showRunner) {
        AdaptiveModalBottomSheet(onDismissRequest = { showRunner = false }) {
            ToolboxSection(
                viewModel = toolboxViewModel,
                onOpenEditor = {
                    showRunner = false
                    toolboxViewModel.invalidateSelection()
                    toolboxState.selected?.let {
                        if (selectedDraft) customViewModel.startDraft(it) else customViewModel.openEditor(it.name)
                    }
                },
            )
        }
    }
    if (showMcp) {
        AdaptiveModalBottomSheet(onDismissRequest = { showMcp = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)) {
                TextButton(onClick = onReload, enabled = !reloading) {
                    Text(stringResource(R.string.settings_reconnect))
                }
                Box(Modifier.weight(1f)) {
                McpSection(
                    entries = entries, statuses = statuses, reloading = reloading,
                    onReload = onReload, onToggle = onToggle,
                    onEdit = { showMcp = false; onEdit(it) }, onDelete = onDelete,
                    onBrowseDirectory = { showMcp = false; onBrowseDirectory() },
                    onImportJson = { showMcp = false; onImportJson() }, onExportJson = onExportJson,
                )
                }
            }
        }
    }
}
