package com.aharou.feature.plugin.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.domain.market.RevericCatalog
import com.aharou.feature.settings.presentation.component.SettingsGroup

@Composable
internal fun RevericMarketPanel(viewModel: RevericMarketViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    var launcherToken by rememberSaveable { mutableStateOf(state.requestToken) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        val token = launcherToken
        launcherToken = null
        viewModel.documentChosen(token, it)
    }
    LaunchedEffect(viewModel, state.busy, state.loaded, state.loadAttempted) {
        if (!state.busy && !state.loaded && !state.loadAttempted) viewModel.load()
    }
    val visible = state.entries.filter {
        state.query.isBlank() || listOf(it.id, it.title(language), it.summary(language), it.author)
            .any { text -> text.contains(state.query.trim(), ignoreCase = true) }
    }
    Column(modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.reveric_source), style = MaterialTheme.typography.labelSmall)
        Text(stringResource(R.string.reveric_scope), style = MaterialTheme.typography.bodySmall)
        AppTextField(value = state.query, onValueChange = viewModel::query,
            placeholder = stringResource(R.string.reveric_filter))
        TextButton(onClick = viewModel::load, enabled = !state.loading && !state.busy) {
            Text(stringResource(R.string.reveric_refresh))
        }
        if (state.loading) CircularProgressIndicator()
        if (state.busy) {
            Text(stringResource(if (state.choosing) R.string.plugin_market_choosing else R.string.reveric_downloading))
            if (!state.choosing) TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.plugin_cancel)) }
        }
        state.notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
        if (state.loaded && visible.isEmpty()) Text(stringResource(R.string.plugin_market_no_matches))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(visible, key = { it.id }) { entry ->
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(entry.title(language), style = MaterialTheme.typography.titleSmall)
                        Text(entry.summary(language), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.reveric_author, entry.author), style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(R.string.reveric_repository, entry.repository), style = MaterialTheme.typography.bodySmall)
                        entry.versions.forEach { version ->
                            Text(stringResource(R.string.reveric_version, version.version, version.pluginApiVersion,
                                version.platforms.joinToString(", "), version.size), style = MaterialTheme.typography.labelMedium)
                            Text(stringResource(R.string.reveric_sha256, version.sha256), style = MaterialTheme.typography.labelSmall)
                            if (!RevericCatalog.canDownload(version)) {
                                Text(stringResource(R.string.reveric_unavailable), style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                Button(enabled = !state.busy && !state.loading && RevericCatalog.canDownload(version), onClick = {
                                    viewModel.chooseDownload(entry, version)?.let { name ->
                                        launcherToken = viewModel.state.value.requestToken
                                        try { launcher.launch(name) } catch (_: Exception) {
                                            viewModel.documentChosen(launcherToken, null)
                                            launcherToken = null
                                        }
                                    }
                                }) { Text(stringResource(R.string.reveric_download)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
