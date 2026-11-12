package com.aharou.feature.plugin.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.domain.market.OperitCatalog
import com.aharou.feature.plugin.domain.market.OperitEntry
import com.aharou.feature.settings.presentation.component.SettingsGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OperitMarketPanel(
    viewModel: OperitMarketViewModel,
    externalBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember(state.result) { mutableStateOf<OperitEntry?>(null) }
    var assetId by remember(selected) { mutableStateOf(selected?.latestAssets?.firstOrNull()?.id) }
    val busy = externalBusy || state.busy
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        viewModel.documentChosen(it)
    }
    val labels = listOf(R.string.operit_type_all, R.string.operit_type_skill, R.string.operit_type_script,
        R.string.operit_type_package, R.string.operit_type_mcp)
    Column(modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.operit_source), style = MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OperitCatalog.types.forEachIndexed { index, type ->
                FilterChip(selected = state.type == type, onClick = { viewModel.type(type) },
                    enabled = !busy, label = { Text(stringResource(labels[index])) })
            }
        }
        AppTextField(value = state.query, onValueChange = viewModel::query,
            placeholder = stringResource(R.string.operit_search))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TextButton(onClick = { viewModel.page(-1) }, enabled = !busy && !state.loading && state.page > 1) {
                Text(stringResource(R.string.operit_previous))
            }
            TextButton(onClick = { viewModel.page(1) }, enabled = !busy && !state.loading && state.result?.hasNext == true) {
                Text(stringResource(R.string.operit_next))
            }
            TextButton(onClick = viewModel::load, enabled = !busy && !state.loading) {
                Text(stringResource(R.string.plugin_market_retry))
            }
        }
        Text(stringResource(R.string.operit_page, state.page, state.result?.total?.toString()
            ?: stringResource(R.string.plugin_market_unknown)), style = MaterialTheme.typography.labelSmall)
        state.result?.let { Text(stringResource(R.string.operit_snapshot, it.generatedAt), style = MaterialTheme.typography.labelSmall) }
        state.notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
        if (state.busy) {
            Text(stringResource(if (state.choosing) R.string.plugin_market_choosing else R.string.operit_busy))
            if (!state.choosing) TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.plugin_cancel)) }
        }
        if (state.loading) CircularProgressIndicator()
        val visible = OperitCatalog.filter(state.result?.items.orEmpty(), state.query)
        if (!state.loading && state.result != null && visible.isEmpty()) Text(stringResource(R.string.plugin_market_no_matches))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(visible, key = { "${it.type}:${it.id}" }) { entry ->
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().clickable { selected = entry }.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(entry.title.ifBlank { entry.id }, style = MaterialTheme.typography.titleSmall)
                        OperitMetadata(entry)
                        Text(entry.description, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.plugin_market_details), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    selected?.let { entry ->
        val asset = entry.latestAssets.firstOrNull { it.id == assetId }
        AdaptiveModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(entry.title, style = MaterialTheme.typography.titleLarge)
                OperitMetadata(entry)
                Text(entry.detail.ifBlank { entry.description })
                Text(stringResource(R.string.operit_compatibility), color = MaterialTheme.colorScheme.primary)
                if (entry.latestAssets.isEmpty()) Text(stringResource(R.string.operit_source_fallback))
                entry.latestAssets.forEach { item ->
                    FilterChip(selected = assetId == item.id, onClick = { assetId = item.id }, enabled = !busy,
                        label = { Text(stringResource(R.string.operit_asset, item.assetName, item.kind)) })
                    Text(stringResource(R.string.operit_checksum, item.sha256?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.operit_unverified)), style = MaterialTheme.typography.labelSmall)
                    Text(marketDownloadDisplay(item.url) ?: stringResource(R.string.plugin_market_url_unavailable),
                        style = MaterialTheme.typography.labelSmall)
                }
                Text(stringResource(R.string.operit_import_warning), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(enabled = !busy && viewModel.canDownload(entry), onClick = {
                        viewModel.chooseDownload(entry, asset)?.let { name ->
                            try { launcher.launch(name) } catch (_: Exception) { viewModel.documentChosen(null) }
                        }
                    }) { Text(stringResource(R.string.operit_download)) }
                    Button(enabled = !busy && viewModel.canImport(entry, asset), onClick = { viewModel.importSkill(entry, asset) }) {
                        Text(stringResource(R.string.operit_import))
                    }
                }
                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.plugin_market_close)) }
            }
        }
    }
}

@Composable
private fun OperitMetadata(entry: OperitEntry) {
    val unknown = stringResource(R.string.plugin_market_unknown)
    Text(stringResource(R.string.operit_metadata, entry.author?.login?.ifBlank { unknown } ?: unknown,
        entry.latestVersion?.version?.ifBlank { unknown } ?: unknown, entry.type,
        entry.latestVersion?.formatVer?.ifBlank { unknown } ?: unknown), style = MaterialTheme.typography.labelMedium)
    Text(stringResource(R.string.operit_entry_source, entry.source?.url?.let(::marketDownloadDisplay) ?: unknown),
        style = MaterialTheme.typography.labelSmall)
}
