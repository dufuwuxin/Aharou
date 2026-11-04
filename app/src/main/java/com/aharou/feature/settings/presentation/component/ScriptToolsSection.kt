package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.tool.script.ScriptToolDefinition
import com.aharou.feature.agent.domain.tool.script.ScriptToolManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScriptToolsViewModel @Inject constructor(private val manager: ScriptToolManager) : ViewModel() {
    val definitions = manager.definitions
    private val mutableError = MutableStateFlow(false)
    val error = mutableError.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    fun change(action: () -> Unit, onSuccess: () -> Unit = {}) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        mutableError.value = false
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableError.value = true
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun save(oldName: String?, name: String, description: String, command: String, enabled: Boolean, onSuccess: () -> Unit) = change({
        val definition = ScriptToolDefinition(name, description, command, enabled)
        if (oldName == null) manager.add(definition) else manager.update(oldName, definition)
    }, onSuccess)

    fun toggle(name: String, enabled: Boolean) = change({ manager.setEnabled(name, enabled) })
    fun remove(name: String) = change({ manager.remove(name) })
}

@Composable
internal fun ScriptToolsSection(
    createRequested: Boolean,
    onCreateHandled: () -> Unit,
    viewModel: ScriptToolsViewModel = hiltViewModel(),
    embedded: Boolean = false,
) {
    val definitions by viewModel.definitions.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var oldName by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("") }
    var enabled by rememberSaveable { mutableStateOf(true) }

    fun create() {
        oldName = null
        name = ""
        description = ""
        command = ""
        enabled = true
        showEditor = true
    }
    LaunchedEffect(createRequested) {
        if (createRequested) {
            create()
            onCreateHandled()
        }
    }

    Column(Modifier.then(if (embedded) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsGroupHeader(stringResource(R.string.script_tools_title))
        if (error) Text(stringResource(R.string.script_tools_save_failed), color = MaterialTheme.colorScheme.error)
        if (showEditor) {
            SettingsGroup {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppTextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.script_tools_name), enabled = !busy)
                    AppTextField(value = description, onValueChange = { description = it }, label = stringResource(R.string.script_tools_description), enabled = !busy)
                    AppTextField(value = command, onValueChange = { command = it }, label = stringResource(R.string.script_tools_command), singleLine = false, minLines = 4, enabled = !busy)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.script_tools_enabled))
                        AppSwitch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
                    }
                    Row {
                        TextButton(enabled = !busy, onClick = {
                            viewModel.save(oldName, name, description, command, enabled) { showEditor = false }
                        }) { Text(stringResource(R.string.script_tools_save)) }
                        TextButton(enabled = !busy, onClick = { showEditor = false }) { Text(stringResource(R.string.script_tools_cancel)) }
                    }
                }
            }
        } else {
            TextButton(enabled = !busy, onClick = { create() }) { Text(stringResource(R.string.script_tools_add)) }
            if (definitions.isEmpty()) Text(stringResource(R.string.script_tools_empty))
            definitions.forEach { definition ->
                SettingsGroup {
                    Column(Modifier.padding(12.dp)) {
                        Text(definition.name, style = MaterialTheme.typography.titleMedium)
                        Text(definition.description, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.script_tools_enabled))
                            AppSwitch(checked = definition.enabled, onCheckedChange = { viewModel.toggle(definition.name, it) }, enabled = !busy)
                        }
                        Row {
                            TextButton(enabled = !busy, onClick = {
                                oldName = definition.name
                                name = definition.name
                                description = definition.description
                                command = definition.command
                                enabled = definition.enabled
                                showEditor = true
                            }) { Text(stringResource(R.string.script_tools_edit)) }
                            TextButton(enabled = !busy, onClick = { viewModel.remove(definition.name) }) { Text(stringResource(R.string.script_tools_delete)) }
                        }
                    }
                }
            }
        }
    }
}
