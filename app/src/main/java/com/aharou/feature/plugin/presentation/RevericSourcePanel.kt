package com.aharou.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.domain.market.MagpieListing
import com.aharou.feature.settings.presentation.component.SettingsGroup
import kotlinx.coroutines.CancellationException

@Composable
internal fun RevericSourcePanel(viewModel: RevericMarketViewModel, modifier: Modifier = Modifier) {
    var showMagpie by rememberSaveable { mutableStateOf(true) }
    Column(modifier) {
        Row(Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(selected = showMagpie, onClick = { showMagpie = true },
                label = { Text(stringResource(R.string.magpie_catalog_title)) })
            FilterChip(selected = !showMagpie, onClick = { showMagpie = false },
                label = { Text(stringResource(R.string.reveric_zip_catalog_title)) })
        }
        if (showMagpie) MagpieMarketPanel(viewModel, Modifier.weight(1f))
        else RevericMarketPanel(viewModel, Modifier.weight(1f))
    }
}

@Composable
private fun MagpieMarketPanel(viewModel: RevericMarketViewModel, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var listings by remember(viewModel) { mutableStateOf<List<MagpieListing>>(emptyList()) }
    var refreshing by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }
    var live by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel, refresh) {
        refreshing = true
        failed = false
        try {
            if (refresh == 0) {
                listings = viewModel.loadMagpie(bundled = true)
                live = false
            } else {
                listings = viewModel.loadMagpie(bundled = false)
                live = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            refreshing = false
        }
    }
    val visible = remember(query, listings) { listings.filter { it.matches(query.trim()) } }
    Column(modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.magpie_catalog_scope), style = MaterialTheme.typography.bodySmall)
        AppTextField(value = query, onValueChange = { query = it },
            placeholder = stringResource(R.string.magpie_catalog_filter))
        Row {
            TextButton(onClick = { refresh++ }, enabled = !refreshing) {
                Text(stringResource(R.string.reveric_refresh))
            }
            if (refreshing) CircularProgressIndicator()
        }
        if (failed) Text(stringResource(R.string.magpie_catalog_refresh_failed), color = MaterialTheme.colorScheme.error)
        if (listings.isNotEmpty()) {
            Text(stringResource(if (live) R.string.magpie_catalog_live else R.string.magpie_catalog_bundled),
                style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.reveric_catalog_count, listings.size, visible.size),
                style = MaterialTheme.typography.labelMedium)
            if (visible.isEmpty()) Text(stringResource(R.string.plugin_market_no_matches))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(visible, key = { it.packageName }) { entry ->
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(entry.name, style = MaterialTheme.typography.titleSmall)
                        Text(entry.packageName, style = MaterialTheme.typography.labelMedium)
                        Text(entry.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
