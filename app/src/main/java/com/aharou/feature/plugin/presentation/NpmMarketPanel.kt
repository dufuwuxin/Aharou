package com.aharou.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.domain.npm.NpmPackageMetadata
import com.aharou.feature.plugin.domain.npm.NpmRegistryError
import com.aharou.feature.settings.presentation.component.SettingsGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NpmMarketPanel(viewModel: NpmMarketViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.browse() }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.npm_market_source), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.npm_market_read_only), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppTextField(
                    value = state.query,
                    onValueChange = viewModel::query,
                    label = stringResource(R.string.npm_market_query_label),
                    placeholder = stringResource(R.string.npm_market_query_hint),
                    isError = state.query.trim().length > NPM_QUERY_LIMIT,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (state.canSearch) viewModel.search() }),
                )
                if (state.query.trim().length > NPM_QUERY_LIMIT) {
                    Text(stringResource(R.string.npm_market_invalid_input), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = viewModel::search, enabled = state.canSearch,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.npm_market_search))
                }
            }
        }
        if (state.query.isBlank() && state.activeQuery == NPM_DISCOVERY_QUERY) {
            item {
                Text(stringResource(R.string.npm_market_discovery, NPM_DISCOVERY_QUERY),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        item {
            when (state.searchStatus) {
                NpmLoadStatus.IDLE -> Text(stringResource(R.string.npm_market_initial))
                NpmLoadStatus.LOADING -> NpmLoading()
                NpmLoadStatus.ERROR -> NpmError(state.searchError, viewModel::retrySearch)
                NpmLoadStatus.READY -> {
                    if (state.page?.packages.isNullOrEmpty()) {
                        Text(stringResource(R.string.npm_market_empty), modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        })
                    }
                }
            }
        }
        items(state.page?.packages.orEmpty()) { metadata ->
            NpmResult(metadata, state.searchStatus == NpmLoadStatus.READY) { viewModel.select(metadata) }
        }
        state.page?.let { page ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(R.string.npm_market_page, page.from / 20 + 1,
                            page.total?.toString() ?: stringResource(R.string.npm_market_unknown)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        TextButton(onClick = viewModel::previous, enabled = state.canGoBack,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.npm_market_previous))
                        }
                        TextButton(onClick = viewModel::next, enabled = state.canGoForward,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.npm_market_next))
                        }
                    }
                }
            }
        }
    }
    state.selected?.let { coordinate ->
        AdaptiveModalBottomSheet(onDismissRequest = viewModel::dismissDetails) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(stringResource(R.string.npm_market_details_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                Text(npmDisplayText(coordinate.name).orEmpty(), style = MaterialTheme.typography.titleSmall)
                Text(npmDisplayText(coordinate.version).orEmpty(), style = MaterialTheme.typography.labelMedium)
                when (state.detailStatus) {
                    NpmLoadStatus.LOADING -> NpmLoading()
                    NpmLoadStatus.ERROR -> NpmError(state.detailError, viewModel::retryDetails)
                    NpmLoadStatus.READY -> state.detail?.let { metadata ->
                        SettingsGroup {
                            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                npmMetadataFields(metadata).forEach { (field, value) ->
                                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                        Text(stringResource(npmFieldLabel(field)), style = MaterialTheme.typography.labelMedium)
                                        Text(value ?: stringResource(R.string.npm_market_unknown),
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    NpmLoadStatus.IDLE -> Unit
                }
                Text(stringResource(R.string.npm_market_detail_notice), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = viewModel::dismissDetails, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.npm_market_close))
                }
            }
        }
    }
}

@Composable
private fun NpmResult(metadata: NpmPackageMetadata, enabled: Boolean, onDetails: () -> Unit) {
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(npmDisplayText(metadata.name) ?: stringResource(R.string.npm_market_unknown),
                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(npmDisplayText(metadata.version) ?: stringResource(R.string.npm_market_unknown),
                style = MaterialTheme.typography.labelMedium)
            Text(npmDisplayText(metadata.description) ?: stringResource(R.string.npm_market_unknown),
                style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val hasCoordinate = !metadata.name.isNullOrBlank() && !metadata.version.isNullOrBlank()
            if (!hasCoordinate) Text(stringResource(R.string.npm_market_missing_coordinate),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDetails, enabled = enabled && hasCoordinate,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.npm_market_details))
            }
        }
    }
}

@Composable
private fun NpmLoading() {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        CircularProgressIndicator()
        Text(stringResource(R.string.npm_market_loading))
    }
}

@Composable
private fun NpmError(error: NpmRegistryError?, onRetry: () -> Unit) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(when (error) {
            NpmRegistryError.INVALID_INPUT -> R.string.npm_market_invalid_input
            NpmRegistryError.RATE_LIMITED -> R.string.npm_market_rate_limited
            NpmRegistryError.REDIRECT_REJECTED -> R.string.npm_market_redirect_rejected
            NpmRegistryError.RESPONSE_TOO_LARGE -> R.string.npm_market_response_too_large
            NpmRegistryError.INVALID_RESPONSE -> R.string.npm_market_invalid_response
            NpmRegistryError.TIMEOUT -> R.string.npm_market_timeout
            NpmRegistryError.HTTP_ERROR -> R.string.npm_market_http_error
            NpmRegistryError.NETWORK, null -> R.string.npm_market_network_error
        }), color = MaterialTheme.colorScheme.error)
        if (error != NpmRegistryError.INVALID_INPUT) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.npm_market_retry))
            }
        }
    }
}

private fun npmFieldLabel(field: NpmMetadataField): Int = when (field) {
    NpmMetadataField.NAME -> R.string.npm_market_field_name
    NpmMetadataField.VERSION -> R.string.npm_market_field_version
    NpmMetadataField.DESCRIPTION -> R.string.npm_market_field_description
    NpmMetadataField.LICENSE -> R.string.npm_market_field_license
    NpmMetadataField.REPOSITORY -> R.string.npm_market_field_repository
    NpmMetadataField.DEPRECATED -> R.string.npm_market_field_deprecated
    NpmMetadataField.INTEGRITY -> R.string.npm_market_field_integrity
}
