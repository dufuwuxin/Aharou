package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.subagent.AgentTeamTaskForm
import com.aharou.feature.agent.domain.subagent.AgentTeamStageForm

internal fun moveTeamTask(tasks: List<AgentTeamTaskForm>, index: Int, delta: Int): List<AgentTeamTaskForm> {
    val target = index + delta
    if (index !in tasks.indices || target !in tasks.indices) return tasks
    return tasks.toMutableList().apply { add(target, removeAt(index)) }
}

internal fun editSharedTeamPrompt(stage: AgentTeamStageForm, prompt: String): AgentTeamStageForm {
    val updated = stage.tasks.filterNot { task ->
        task.agent.isBlank() && task.department.isBlank() && task.agentQuery.isBlank() && task.prompt.isBlank() &&
            task.writePaths.isBlank() && task.interfaces.isEmpty()
    }.map { task ->
        if (task.prompt.isBlank() || task.prompt == stage.prompt) task.copy(prompt = prompt) else task
    }
    val existing = updated.map { it.agent.lowercase() }.toSet()
    val added = if (prompt.isBlank()) emptyList() else stage.members.filter { it.lowercase() !in existing }
        .map { AgentTeamTaskForm(agent = it, prompt = prompt) }
    return stage.copy(prompt = prompt, tasks = updated + added)
}

/** Shared prompt is a fallback only: explicit tasks already carry their effective prompts. */
internal fun taskEditorStageForSave(stage: AgentTeamStageForm): AgentTeamStageForm =
    if (stage.tasks.any { it.prompt.isNotBlank() }) stage.copy(prompt = "") else stage

@Composable
internal fun TeamTaskEditor(tasks: List<AgentTeamTaskForm>, onChange: (List<AgentTeamTaskForm>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.team_task_editor_title), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.team_task_editor_hint), style = MaterialTheme.typography.bodySmall)
        tasks.forEachIndexed { index, task -> key(task.id) {
            fun update(value: AgentTeamTaskForm) { onChange(tasks.mapIndexed { i, old -> if (i == index) value else old }) }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(Modifier.fillMaxWidth()) {
                    IconButton(onClick = { onChange(moveTeamTask(tasks, index, -1)) }, enabled = index > 0) {
                        Icon(Icons.Default.ArrowUpward, stringResource(R.string.team_workflow_move_up))
                    }
                    IconButton(onClick = { onChange(moveTeamTask(tasks, index, 1)) }, enabled = index < tasks.lastIndex) {
                        Icon(Icons.Default.ArrowDownward, stringResource(R.string.team_workflow_move_down))
                    }
                    IconButton(onClick = { onChange(tasks.filterIndexed { i, _ -> i != index }) }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.common_delete))
                    }
                }
                AppTextField(value = task.agent, onValueChange = { update(task.copy(agent = it)) },
                    label = stringResource(R.string.team_task_agent), singleLine = true)
                // Existing selector-based tasks keep department/query and interfaces on copy.
                AppTextField(value = task.prompt, onValueChange = { update(task.copy(prompt = it)) },
                    label = stringResource(R.string.team_task_prompt), singleLine = false, minLines = 2, maxLines = 6)
                AppTextField(value = task.writePaths, onValueChange = { update(task.copy(writePaths = it)) },
                    label = stringResource(R.string.team_task_write_paths), singleLine = false, maxLines = 3)
            }
        } }
        TextButton(onClick = { onChange(tasks + AgentTeamTaskForm()) }) {
            Text(stringResource(R.string.team_task_add))
        }
    }
}
