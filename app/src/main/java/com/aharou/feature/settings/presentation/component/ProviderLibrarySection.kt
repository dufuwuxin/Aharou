package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.ExpandableChevronIcon
import com.aharou.feature.settings.data.local.ProviderLibraryPreferences
import com.aharou.feature.settings.data.local.ProviderPreset
import com.aharou.feature.settings.data.local.ProviderPresetLibrary
import com.aharou.feature.settings.domain.model.AIProviderConfig
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.lazy.rememberLazyListState

internal fun providerPresetBrand(preset: ProviderPreset): String = modelBrandKey(preset.name).takeUnless { it == "other" }
    ?: preset.id.lowercase().replace(Regex("[^a-z0-9_-]"), "_")

internal fun providerLibraryBrand(provider: AIProviderConfig, presets: List<ProviderPreset>): String {
    presets.firstOrNull { it.name.equals(provider.name, true) || it.baseUrl.trimEnd('/') == provider.baseUrl.trimEnd('/') }
        ?.let { return providerPresetBrand(it) }
    val host = runCatching { java.net.URI(provider.baseUrl).host }.getOrNull()
    if (host != null) presets.filter { runCatching { java.net.URI(it.baseUrl).host }.getOrNull() == host }
        .map(::providerPresetBrand).distinct().singleOrNull()?.let { return it }
    return modelBrandKey(provider.name).takeUnless { it == "other" }
        ?: modelBrandKey(provider.baseUrl).takeUnless { it == "other" } ?: "other"
}

@Composable
internal fun ProvidersSection(
    providers: List<AIProviderConfig>,
    onEdit: (AIProviderConfig) -> Unit,
    onDelete: (AIProviderConfig) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onAdd: (ProviderPreset) -> Unit = {},
) {
    val context = LocalContext.current
    val presets = remember(context) { ProviderPresetLibrary.loadOfficial(context) }
    val hidden by ProviderLibraryPreferences.hiddenTypes.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var visibilityFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val connections = providers.groupBy { providerLibraryBrand(it, presets) }
    val catalog = presets.groupBy(::providerPresetBrand)
    val groups = (connections.keys + catalog.keys).distinct().sortedBy { brandDisplayName(context, it).lowercase() }
        .filter { key -> (showHidden || key !in hidden) &&
            (query.isBlank() || brandDisplayName(context, key).contains(query, true) ||
                connections[key].orEmpty().any { it.name.contains(query, true) || it.baseUrl.contains(query, true) }) }
    val state = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(state) { from, to ->
        val first = providers.indexOfFirst { it.id == from.key }
        val last = providers.indexOfFirst { it.id == to.key }
        if (first >= 0 && last >= 0) onReorder(first, last)
    }
    Column(Modifier.fillMaxSize()) {
        com.aharou.core.ui.AppTextField(query, { query = it }, placeholder = stringResource(R.string.providers_library_search),
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg))
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.providers_library_count, providers.size), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { showHidden = !showHidden }, modifier = Modifier.testTag("providers_show_hidden")) {
                Text(stringResource(if (showHidden) R.string.providers_hide_hidden else R.string.providers_show_hidden))
            }
        }
        if (visibilityFailed) Text(stringResource(R.string.permission_save_failed), color = MaterialTheme.colorScheme.error)
        LazyColumn(state = state, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            groups.forEach { key ->
                val entries = connections[key].orEmpty()
                val open = key in expanded || query.isNotBlank()
                item(key = "brand:$key", contentType = "brand") {
                    SettingsGroup {
                        SettingsRow(title = brandDisplayName(context, key), subtitle = stringResource(R.string.providers_brand_connections, entries.size),
                            onClick = { expanded = if (key in expanded) expanded - key else expanded + key },
                            trailing = { ExpandableChevronIcon(expanded = open) },
                            modifier = Modifier.testTag("provider_brand_$key"))
                    }
                }
                if (open) {
                    items(entries, key = { it.id }, contentType = { "connection" }) { provider ->
                        ReorderableItem(state = reorder, key = provider.id) { _ ->
                            SettingsGroup {
                                ProviderItem(provider, { onEdit(provider) }, { onDelete(provider) }, dragModifier = Modifier.longPressDraggableHandle())
                            }
                        }
                    }
                    item(key = "brand-actions:$key", contentType = "actions") {
                        val preset = catalog[key]?.firstOrNull() ?: entries.firstOrNull()?.let { provider ->
                            ProviderPreset(key, brandDisplayName(context, key), provider.type.name, provider.baseUrl)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (preset != null) TextButton(onClick = { onAdd(preset) }, modifier = Modifier.testTag("provider_add_url_$key")) {
                                Text(stringResource(R.string.providers_add_url))
                            }
                            TextButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try { ProviderLibraryPreferences.setHidden(if (key in hidden) hidden - key else hidden + key); visibilityFailed = false }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { visibilityFailed = true }
                            } }) {
                                Text(stringResource(if (key in hidden) R.string.providers_show_type else R.string.providers_hide_type))
                            }
                        }
                        Text(stringResource(R.string.providers_login_next_version), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (groups.isEmpty()) item { Text(stringResource(R.string.providers_library_empty)) }
        }
    }
}
