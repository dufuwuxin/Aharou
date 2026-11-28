package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aharou.feature.agent.domain.skill.SkillScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A model-proposed draft. It is inert until the user edits and explicitly saves it. */
internal fun maySaveSkillEvolutionDraft(
    optedIn: Boolean,
    sameSession: Boolean,
    currentDraft: Boolean,
    scope: SkillScope,
    expectedWorkspaceId: String? = null,
    currentWorkspaceId: String? = null
): Boolean = optedIn && sameSession && currentDraft && scope != SkillScope.BUILTIN &&
    (scope != SkillScope.PROJECT || expectedWorkspaceId != null && expectedWorkspaceId == currentWorkspaceId)

data class SkillEvolutionSuggestion(
    val name: String,
    val description: String,
    val instructions: String,
    val draftId: String = java.util.UUID.randomUUID().toString(),
    val projectWorkspaceId: String? = null
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val validName = Regex("[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}")

        fun maySuggest(enabled: Boolean, successful: Boolean, toolCalls: Int, autoTriggered: Boolean): Boolean =
            enabled && successful && toolCalls > 0 && !autoTriggered

        fun parse(raw: String): SkillEvolutionSuggestion? = runCatching {
            val values = json.parseToJsonElement(raw.trim()).jsonObject
            val name = values["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val description = values["description"]?.jsonPrimitive?.content?.trim().orEmpty()
            val instructions = values["instructions"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (!validName.matches(name) || description.isBlank() || instructions.isBlank()) return null
            SkillEvolutionSuggestion(name, description, instructions)
        }.getOrNull()
    }
}

@Composable
fun SkillEvolutionSuggestionDialog(
    suggestion: SkillEvolutionSuggestion,
    onDismiss: () -> Unit,
    onSave: (String, String, String, SkillScope) -> Boolean
) {
    var name by remember(suggestion) { mutableStateOf(suggestion.name) }
    var description by remember(suggestion) { mutableStateOf(suggestion.description) }
    var instructions by remember(suggestion) { mutableStateOf(suggestion.instructions) }
    var scope by remember(suggestion) { mutableStateOf<SkillScope?>(null) }
    var failed by remember(suggestion) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("技能改进草案") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("技能名") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("描述") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(instructions, { instructions = it }, label = { Text("技能正文") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
                Text("选择保存范围")
                FilterChip(selected = scope == SkillScope.GLOBAL, onClick = { scope = SkillScope.GLOBAL }, label = { Text("GLOBAL") })
                FilterChip(selected = scope == SkillScope.PROJECT, onClick = { scope = SkillScope.PROJECT }, label = { Text("PROJECT") })
                if (failed) Text("保存失败或名称与内置技能冲突；草案仍未保存")
            }
        },
        confirmButton = {
            TextButton(
                enabled = scope != null && name.isNotBlank() && description.isNotBlank() && instructions.isNotBlank(),
                onClick = { failed = !onSave(name.trim(), description.trim(), instructions.trim(), scope ?: return@TextButton) }
            ) { Text("确认并保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("拒绝") } }
    )
}
