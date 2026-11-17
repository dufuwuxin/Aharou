package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.settings.data.repository.ContextPreferences
import com.aharou.feature.settings.data.repository.ContextPreset
import com.aharou.feature.settings.presentation.ContextSettingsViewModel

@Composable
internal fun ContextManagementSection(viewModel: ContextSettingsViewModel = hiltViewModel()) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    ContextManagementContent(preferences, viewModel::applyPreset, viewModel::setRounds,
        viewModel::setSoft, viewModel::setHard, viewModel::setPreserveSummary, viewModel::setKeepTools)
}

@Composable
internal fun ContextManagementContent(
    preferences: ContextPreferences,
    onPreset: (ContextPreset) -> Unit,
    onRounds: (Int) -> Unit,
    onSoft: (Int) -> Unit,
    onHard: (Int) -> Unit,
    onPreserveSummary: (Boolean) -> Unit,
    onKeepTools: (Int) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsGroupHeader(stringResource(R.string.settings_context_management))
    SettingsGroup {
        SettingsRow(title = stringResource(R.string.settings_context_preset),
            subtitle = stringResource(R.string.settings_context_layers_desc),
            trailing = { Text(stringResource(preferences.preset.labelRes())) })
        ContextPreset.entries.filter { it != ContextPreset.CUSTOM }.forEach { preset ->
            SettingsDivider()
            SettingsRow(title = stringResource(preset.labelRes()),
                subtitle = stringResource(preset.descriptionRes()),
                modifier = Modifier.testTag("context_preset_${preset.id}"),
                onClick = { onPreset(preset) })
        }
        SettingsDivider()
        SettingsRow(title = stringResource(R.string.settings_context_custom),
            modifier = Modifier.testTag("context_custom"), onClick = { expanded = true })
        SettingsDivider()
        SettingsRow(title = stringResource(R.string.settings_context_advanced),
            modifier = Modifier.testTag("context_advanced"), onClick = { expanded = !expanded })
        if (expanded) {
            ContextNumberRow("rounds", R.string.settings_context_trim_title, preferences.rounds) { editing = "rounds" }
            ContextNumberRow("soft", R.string.settings_soft_compaction_threshold, preferences.soft) { editing = "soft" }
            ContextNumberRow("hard", R.string.settings_compaction_threshold, preferences.hard) { editing = "hard" }
            SettingsDivider()
            SettingsRow(title = stringResource(R.string.settings_context_preserve_summary),
                subtitle = stringResource(R.string.settings_context_preserve_summary_desc),
                trailing = { AppSwitch(checked = preferences.preserveSummary, onCheckedChange = onPreserveSummary) })
            ContextNumberRow("tools", R.string.settings_context_keep_tools, preferences.keepTools) { editing = "tools" }
        }
    }
    editing?.let { field ->
        val initial = when (field) {
            "rounds" -> preferences.rounds
            "soft" -> preferences.soft
            "hard" -> preferences.hard
            else -> preferences.keepTools
        }
        val title = when (field) {
            "rounds" -> R.string.settings_context_trim_title
            "soft" -> R.string.settings_soft_compaction_threshold
            "hard" -> R.string.settings_compaction_threshold
            else -> R.string.settings_context_keep_tools
        }
        ContextNumberDialog(stringResource(title), initial, if (field == "soft" || field == "hard") 1 else 0,
            onSave = { value ->
                when (field) {
                    "rounds" -> onRounds(value)
                    "soft" -> onSoft(value)
                    "hard" -> onHard(value)
                    else -> onKeepTools(value)
                }
                editing = null
            }, onDismiss = { editing = null })
    }
}

@Composable
private fun ContextNumberRow(key: String, title: Int, value: Int, onClick: () -> Unit) {
    SettingsDivider()
    SettingsRow(title = stringResource(title), modifier = Modifier.testTag("context_$key"),
        onClick = onClick, trailing = { Text(value.toString()) })
}

@Composable
private fun ContextNumberDialog(title: String, initial: Int, min: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf(initial.toString()) }
    val value = input.trim().toIntOrNull()
    val valid = value != null && value in min..100
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            AppTextField(value = input, onValueChange = { input = it }, isError = !valid,
                modifier = Modifier.testTag("context_number_input"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(stringResource(R.string.settings_context_number_range, min)) })
        }
    }, confirmButton = {
        TextButton(enabled = valid, onClick = { value?.let(onSave) }) { Text(stringResource(R.string.common_save)) }
    }, dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
    }, containerColor = MaterialTheme.colorScheme.surface,
        properties = DialogProperties(usePlatformDefaultWidth = false))
}

private fun ContextPreset.labelRes(): Int = when (this) {
    ContextPreset.BALANCED -> R.string.settings_context_balanced
    ContextPreset.ECONOMY -> R.string.settings_context_economy
    ContextPreset.DETAILED -> R.string.settings_context_detailed
    ContextPreset.CUSTOM -> R.string.settings_context_custom
}

private fun ContextPreset.descriptionRes(): Int = when (this) {
    ContextPreset.BALANCED -> R.string.settings_context_balanced_desc
    ContextPreset.ECONOMY -> R.string.settings_context_economy_desc
    ContextPreset.DETAILED -> R.string.settings_context_detailed_desc
    ContextPreset.CUSTOM -> R.string.settings_context_layers_desc
}
