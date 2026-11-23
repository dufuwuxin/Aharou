package com.aharou.feature.plugin.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.domain.market.RevericCatalog
import com.aharou.feature.plugin.domain.market.RevericEntry
import com.aharou.feature.plugin.domain.market.RevericVersion
import com.aharou.feature.settings.presentation.component.SettingsGroup

@Composable
internal fun RevericMarketPanel(viewModel: RevericMarketViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.entries.firstOrNull { it.id == selectedId }
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
        if (selected == null) RevericDownloadStatus(state, viewModel::cancel)
        if (state.loaded) {
            Text(stringResource(R.string.reveric_catalog_count, state.entries.size, visible.size),
                style = MaterialTheme.typography.labelMedium)
            if (visible.isEmpty()) Text(stringResource(R.string.plugin_market_no_matches))
        }
        LazyColumn(Modifier.weight(1f).testTag("reveric_catalog_list"),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(visible, key = { it.id }) { entry ->
                SettingsGroup {
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.reveric_details)) {
                                selectedId = entry.id
                            }
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(entry.title(language), style = MaterialTheme.typography.titleSmall)
                        Text(entry.summary(language), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.reveric_author, entry.author), style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(R.string.reveric_details), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    if (selected != null) {
        RevericDetails(selected, language, state, onDismiss = { selectedId = null }, onCancel = viewModel::cancel) { version ->
            viewModel.chooseDownload(selected, version)?.let { name ->
                launcherToken = viewModel.state.value.requestToken
                try {
                    launcher.launch(name)
                } catch (_: Exception) {
                    viewModel.documentChosen(launcherToken, null)
                    launcherToken = null
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RevericDetails(
    entry: RevericEntry,
    language: String,
    state: RevericMarketState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onDownload: (RevericVersion) -> Unit,
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title(language), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            }
            RevericDownloadStatus(state, onCancel)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(entry.summary(language), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.reveric_plugin_id, entry.id), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.reveric_author, entry.author), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.reveric_repository, entry.repository), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.reveric_detail_scope), style = MaterialTheme.typography.bodySmall)
                entry.versions.forEach { version ->
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(stringResource(R.string.reveric_version, version.version, version.pluginApiVersion,
                                version.platforms.joinToString(", "), version.size), style = MaterialTheme.typography.labelMedium)
                            Text(stringResource(R.string.reveric_sha256, version.sha256), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.reveric_source_commit, version.sourceCommit), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.reveric_release_url, version.releaseUrl), style = MaterialTheme.typography.bodySmall)
                            val canDownload = RevericCatalog.canDownload(version)
                            if (!canDownload) {
                                Text(stringResource(R.string.reveric_unavailable), style = MaterialTheme.typography.bodySmall)
                            }
                            Button(enabled = !state.busy && !state.loading && canDownload, onClick = { onDownload(version) }) {
                                Text(stringResource(R.string.reveric_download))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RevericDownloadStatus(state: RevericMarketState, onCancel: () -> Unit) {
    if (state.busy) {
        Text(stringResource(if (state.choosing) R.string.plugin_market_choosing else R.string.reveric_downloading))
        if (!state.choosing) TextButton(onClick = onCancel) { Text(stringResource(R.string.plugin_cancel)) }
    }
    state.notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
}
