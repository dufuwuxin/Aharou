package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowEntry
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowForm
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowSaveError
import com.aharou.feature.agent.domain.teamworkflow.WorkflowTeamRefForm
import com.aharou.feature.settings.presentation.TeamWorkflowSettingsState
import com.aharou.feature.settings.presentation.TeamWorkflowSettingsViewModel

@Composable
internal fun TeamWorkflowsSection(viewModel: TeamWorkflowSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TeamWorkflowsContent(
        state, viewModel::startCreate, viewModel::startEdit, viewModel::requestDelete,
        viewModel::updateForm, viewModel::save, viewModel::dismissEditor,
        viewModel::confirmDelete, viewModel::dismissDelete, viewModel::refresh
    )
}

@Composable
private fun TeamWorkflowsContent(
    state: TeamWorkflowSettingsState,
    onCreate: () -> Unit,
    onEdit: (TeamWorkflowEntry) -> Unit,
    onDelete: (TeamWorkflowEntry) -> Unit,
    onChange: (TeamWorkflowForm) -> Unit,
    onSave: () -> Unit,
    onDismissEditor: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.team_workflows_title))
        Text(stringResource(R.string.team_workflows_hint), style = MaterialTheme.typography.bodySmall)
        SettingsGroup {
            SettingsRow(title = stringResource(R.string.team_workflow_new), onClick = onCreate,
                enabled = !state.saving && !state.deleting)
            state.workflows.forEach { entry ->
                SettingsDivider()
                SwipeToDeleteRow(onDelete = { onDelete(entry) }, onClick = { onEdit(entry) }) {
                    SettingsRow(
                        title = entry.workflow.name,
                        subtitle = stringResource(R.string.team_workflow_summary,
                            stringResource(if (entry.scope == AgentDefinitionScope.GLOBAL) R.string.team_scope_global else R.string.team_scope_project),
                            entry.workflow.teams.joinToString(" → ") { it.teamName })
                    )
                }
            }
        }
        if (!state.loading && state.workflows.isEmpty() && !state.loadFailed) {
            Text(stringResource(R.string.team_workflows_empty), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.loading) Text(stringResource(R.string.team_workflows_loading))
        if (state.loadFailed) {
            Text(stringResource(R.string.team_workflows_load_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.common_retry)) }
        }
    }
    state.editor?.let { editor ->
        WorkflowEditorSheet(state, onChange, onSave, onDismissEditor, editor.originalName != null)
    }
    state.pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.team_workflow_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.team_workflow_delete_message, entry.workflow.name))
                    if (state.deleteFailed) Text(stringResource(R.string.team_workflow_delete_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.deleting) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete, enabled = !state.deleting) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowEditorSheet(
    state: TeamWorkflowSettingsState,
    onChange: (TeamWorkflowForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    editing: Boolean
) {
    val form = state.editor?.form ?: return
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(stringResource(if (editing) R.string.team_workflow_edit else R.string.team_workflow_new),
                style = MaterialTheme.typography.titleMedium)
            state.saveError?.let { Text(stringResource(it.workflowMessageRes()), color = MaterialTheme.colorScheme.error) }
            AppTextField(form.name, { onChange(form.copy(name = it)) }, label = stringResource(R.string.team_editor_name), enabled = !state.saving)
            AppTextField(form.description, { onChange(form.copy(description = it)) }, label = stringResource(R.string.team_editor_description),
                singleLine = false, minLines = 2, enabled = !state.saving)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AgentDefinitionScope.entries.forEach { scope ->
                    FilterChip(selected = form.scope == scope, enabled = !editing && !state.saving,
                        onClick = { onChange(form.copy(scope = scope)) },
                        label = { Text(stringResource(if (scope == AgentDefinitionScope.GLOBAL) R.string.team_scope_global else R.string.team_scope_project)) })
                }
            }
            SettingsGroupHeader(text = stringResource(R.string.team_workflow_chain))
            if (state.availableTeams.isEmpty()) Text(stringResource(R.string.team_workflow_no_teams))
            form.teams.forEachIndexed { index, ref ->
                fun change(value: WorkflowTeamRefForm) {
                    onChange(form.copy(teams = form.teams.mapIndexed { i, old -> if (i == index) value else old }))
                }
                SettingsGroup {
                    var choosing by remember { mutableStateOf(false) }
                    Box {
                        SettingsRow(title = stringResource(R.string.team_workflow_layer, index + 1),
                            subtitle = ref.team.ifBlank { stringResource(R.string.team_workflow_choose_team) },
                            onClick = { choosing = true }, enabled = !state.saving)
                        DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                            state.availableTeams.forEach { team ->
                                DropdownMenuItem(text = { Text(team) }, onClick = { change(ref.copy(team = team)); choosing = false })
                            }
                        }
                    }
                    SettingsRow(title = stringResource(R.string.team_workflow_accepts_reject), trailing = {
                        AppSwitch(ref.acceptsReject, { change(ref.copy(acceptsReject = it)) }, enabled = !state.saving)
                    })
                    AppTextField(ref.lead, { change(ref.copy(lead = it)) }, label = stringResource(R.string.team_workflow_lead),
                        enabled = !state.saving, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg))
                    Row {
                        TextButton(enabled = index > 0 && !state.saving, onClick = {
                            val refs = form.teams.toMutableList()
                            refs[index] = refs[index - 1].also { refs[index - 1] = ref }
                            onChange(form.copy(teams = refs))
                        }) { Text(stringResource(R.string.team_workflow_move_up)) }
                        TextButton(enabled = index < form.teams.lastIndex && !state.saving, onClick = {
                            val refs = form.teams.toMutableList()
                            refs[index] = refs[index + 1].also { refs[index + 1] = ref }
                            onChange(form.copy(teams = refs))
                        }) { Text(stringResource(R.string.team_workflow_move_down)) }
                        TextButton(enabled = !state.saving, onClick = {
                            onChange(form.copy(teams = form.teams.filterIndexed { i, _ -> i != index }))
                        }) { Text(stringResource(R.string.common_delete)) }
                    }
                }
            }
            TextButton(enabled = !state.saving, onClick = { onChange(form.copy(teams = form.teams + WorkflowTeamRefForm())) }) {
                Text(stringResource(R.string.team_workflow_add_layer))
            }
            AppTextField(form.maxRejectRounds, { onChange(form.copy(maxRejectRounds = it)) },
                label = stringResource(R.string.team_workflow_max_reject_rounds), enabled = !state.saving)
            AppTextField(form.charter, { onChange(form.copy(charter = it)) }, label = stringResource(R.string.team_workflow_charter),
                singleLine = false, minLines = 3, enabled = !state.saving)
            Button(onClick = onSave, enabled = !state.saving && !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.saving) R.string.team_workflow_saving else R.string.common_save))
            }
            TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

private fun TeamWorkflowSaveError.workflowMessageRes(): Int = when (this) {
    TeamWorkflowSaveError.INVALID_NAME -> R.string.team_workflow_error_name
    TeamWorkflowSaveError.TOO_FEW_TEAMS -> R.string.team_workflow_error_few_teams
    TeamWorkflowSaveError.EMPTY_TEAM_REF -> R.string.team_workflow_error_empty_team
    TeamWorkflowSaveError.DUPLICATE_TEAM -> R.string.team_workflow_error_duplicate_team
    TeamWorkflowSaveError.UNKNOWN_TEAM -> R.string.team_workflow_error_unknown_team
    TeamWorkflowSaveError.NAME_CONFLICT -> R.string.team_workflow_error_conflict
    TeamWorkflowSaveError.IO_FAILED -> R.string.team_workflow_error_io
}
