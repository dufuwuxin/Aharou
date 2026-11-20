package com.aharou.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteMember
import com.aharou.feature.settings.presentation.ModelRouteGroupViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
internal fun ModelRouteGroupsSection(providers: List<AIProviderConfig>, onBack: () -> Unit,
    viewModel: ModelRouteGroupViewModel = hiltViewModel()) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedGroupId.collectAsStateWithLifecycle(initialValue = "")
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler { if (editing) editing = false else onBack() }
    if (editing) {
        key(editingId) {
            ModelRouteEditor(groups.firstOrNull { it.id == editingId }, providers, onBack = { editing = false }, onSave = { name, models ->
                if (editingId == null) viewModel.create(name, models) else viewModel.update(requireNotNull(editingId), name, models)
                editing = false
            })
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.lg)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            TextButton(onClick = { editingId = null; editing = true }, modifier = Modifier.testTag("route_group_create")) {
                Text(stringResource(R.string.route_group_create))
            }
        }
        Text(stringResource(R.string.route_groups_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.route_groups_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.sm))
        if (error) Text(stringResource(R.string.route_group_failed), color = MaterialTheme.colorScheme.error)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.md), contentPadding = PaddingValues(bottom = Spacing.xl)) {
            groups.forEach { group -> item(key = group.id) {
                SettingsGroup {
                    SettingsRow(title = group.name, subtitle = group.models.mapIndexed { index, member -> "${index + 1}. ${member.model}" }.joinToString("\n"),
                        onClick = { editingId = group.id; editing = true })
                    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { scope.launch {
                            try { viewModel.select(group.id); error = false }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = true }
                        } }, enabled = selectedId != group.id, modifier = Modifier.testTag("route_group_select_${group.id}")) {
                            Text(stringResource(if (selectedId == group.id) R.string.route_group_selected else R.string.route_group_use))
                        }
                        TextButton(onClick = { deletingId = group.id }) { Text(stringResource(R.string.common_delete)) }
                    }
                }
            } }
            if (groups.isEmpty()) item { Text(stringResource(R.string.route_groups_empty)) }
        }
    }
    deletingId?.let { id -> AlertDialog(onDismissRequest = { deletingId = null }, title = { Text(stringResource(R.string.common_delete)) },
        text = { Text(stringResource(R.string.route_group_delete_hint)) },
        confirmButton = { TextButton(onClick = { scope.launch {
            try { viewModel.delete(id); deletingId = null; error = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true; deletingId = null }
        } }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.common_cancel)) } }) }
}

@Composable
private fun ModelRouteEditor(initial: ModelRouteGroup?, providers: List<AIProviderConfig>, onBack: () -> Unit,
    onSave: suspend (String, List<ModelRouteMember>) -> Unit) {
    fun memberKey(provider: String, model: String) = Json.encodeToString(listOf(provider, model))
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var selected by rememberSaveable { mutableStateOf(initial?.models.orEmpty().map { memberKey(it.providerId, it.model) }) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = saving) { }
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.lg)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack, enabled = !saving) { Text(stringResource(R.string.common_cancel)) }
            TextButton(enabled = !saving && name.isNotBlank() && selected.isNotEmpty(), modifier = Modifier.testTag("route_group_save"), onClick = {
                saving = true
                scope.launch {
                    try { onSave(name.trim(), selected.map { key -> Json.decodeFromString<List<String>>(key).let { ModelRouteMember(it[0], it[1]) } }) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = true }
                    finally { saving = false }
                }
            }) { Text(stringResource(R.string.common_save)) }
        }
        AppTextField(name, { name = it }, label = stringResource(R.string.common_name), modifier = Modifier.fillMaxWidth().testTag("route_group_name"))
        if (error) Text(stringResource(R.string.route_group_failed), color = MaterialTheme.colorScheme.error)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm), contentPadding = PaddingValues(vertical = Spacing.md)) {
            selected.forEachIndexed { index, encoded -> item(key = "selected:$index:$encoded") {
                val values = Json.decodeFromString<List<String>>(encoded)
                SettingsGroup {
                    SettingsRow(title = "${index + 1}. ${values[1]}", subtitle = providers.firstOrNull { it.id == values[0] }?.name ?: values[0],
                        trailing = {
                            TextButton(enabled = !saving && index > 0, onClick = { selected = selected.toMutableList().apply { add(index - 1, removeAt(index)) } }) { Text(stringResource(R.string.route_group_move_up)) }
                            TextButton(enabled = !saving, modifier = Modifier.testTag("route_group_remove_$index"),
                                onClick = { selected = selected.toMutableList().apply { removeAt(index) } }) { Text(stringResource(R.string.common_delete)) }
                        })
                }
            } }
            providers.filter { it.isEnabled }.forEach { provider ->
                item(key = "provider:${provider.id}") { SettingsGroupHeader(text = provider.name) }
                provider.models.forEach { model -> item(key = "model:${memberKey(provider.id, model)}") {
                    val encoded = memberKey(provider.id, model)
                    FilterChip(selected = encoded in selected, enabled = !saving, onClick = {
                        selected = if (encoded in selected) selected - encoded else selected + encoded
                    }, label = { Text(model) }, modifier = Modifier.testTag("route_model_${provider.id}_$model"))
                } }
            }
        }
    }
}
