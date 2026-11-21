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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.onboardingTarget
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.ExpandableChevronIcon
import com.aharou.feature.settings.data.local.ProviderLibraryPreferences
import com.aharou.feature.settings.data.local.ProviderPreset
import com.aharou.feature.settings.data.local.ProviderPresetLibrary
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
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
    var managingVisibility by rememberSaveable { mutableStateOf(false) }
    var showAddPicker by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var visibilityFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val connections = providers.groupBy { providerLibraryBrand(it, presets) }
    val catalog = presets.groupBy(::providerPresetBrand)
    val groups = (connections.keys + catalog.keys).distinct().sortedBy { brandDisplayName(context, it).lowercase() }
        .filter { key -> managingVisibility || (key !in hidden &&
            (query.isBlank() || brandDisplayName(context, key).contains(query, true) ||
                connections[key].orEmpty().any { it.name.contains(query, true) || it.baseUrl.contains(query, true) })) }
    fun updateVisibility(key: String, visible: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                ProviderLibraryPreferences.setBrandHidden(key, hidden = !visible)
                visibilityFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                visibilityFailed = true
            }
        }
    }
    val state = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(state) { from, to ->
        val first = providers.indexOfFirst { it.id == from.key }
        val last = providers.indexOfFirst { it.id == to.key }
        if (first >= 0 && last >= 0) onReorder(first, last)
    }
    Column(Modifier.fillMaxSize()) {
        if (!managingVisibility) {
            com.aharou.core.ui.AppTextField(query, { query = it }, placeholder = stringResource(R.string.providers_library_search),
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).testTag("providers_library_search"))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.providers_library_count, providers.size), style = MaterialTheme.typography.labelMedium)
            if (!managingVisibility) {
                TextButton(
                    onClick = { showAddPicker = true },
                    modifier = Modifier.onboardingTarget(OnboardingStep.PROVIDER_ADD),
                ) {
                    Text(stringResource(R.string.settings_add_provider))
                }
            }
            TextButton(onClick = { managingVisibility = !managingVisibility; query = "" }, modifier = Modifier.testTag("providers_show_hidden")) {
                Text(if (managingVisibility) stringResource(R.string.common_done) else
                    "${stringResource(R.string.common_show)}/${stringResource(R.string.common_hide)}")
            }
        }
        if (visibilityFailed) Text(stringResource(R.string.permission_save_failed), color = MaterialTheme.colorScheme.error)
        LazyColumn(state = state, modifier = Modifier.weight(1f).testTag("providers_library_list"),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            groups.forEach { key ->
                val entries = connections[key].orEmpty()
                val open = key in expanded || query.isNotBlank()
                item(key = "brand:$key", contentType = "brand") {
                    val name = brandDisplayName(context, key)
                    SettingsGroup {
                        if (managingVisibility) {
                            SettingsRow(title = name, modifier = Modifier.testTag("provider_brand_$key"), trailing = {
                                AppSwitch(
                                    checked = key !in hidden,
                                    onCheckedChange = { visible -> updateVisibility(key, visible) },
                                    modifier = Modifier.testTag("provider_visibility_$key").semantics { contentDescription = name },
                                )
                            })
                        } else {
                            SettingsRow(title = name, subtitle = stringResource(R.string.providers_brand_connections, entries.size),
                                onClick = { expanded = if (key in expanded) expanded - key else expanded + key },
                                trailing = { ExpandableChevronIcon(expanded = open) },
                                modifier = Modifier.testTag("provider_brand_$key"))
                        }
                    }
                }
                if (!managingVisibility && open) {
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
                            TextButton(onClick = { updateVisibility(key, visible = false) }) {
                                Text(stringResource(R.string.providers_hide_type))
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
    if (showAddPicker) {
        ProviderPresetSheet(
            onDismiss = { showAddPicker = false },
            onSelectCustom = {
                showAddPicker = false
                onAdd(ProviderPreset("custom", "", ProviderType.OPENAI.name, ""))
            },
            onSelectOfficial = {
                showAddPicker = false
                onAdd(it)
            },
        )
    }
}
