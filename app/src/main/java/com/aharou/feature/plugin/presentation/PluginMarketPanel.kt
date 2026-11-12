package com.aharou.feature.plugin.presentation

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import com.aharou.feature.plugin.domain.market.PluginMarketEntry
import com.aharou.feature.settings.presentation.ExtensionsViewModel
import com.aharou.feature.settings.presentation.component.SettingsGroup
import java.net.URI

internal enum class MarketVersionState(@param:StringRes val label: Int) {
    AVAILABLE(R.string.plugin_market_available),
    INSTALLED(R.string.plugin_market_same_version),
    UPDATE(R.string.plugin_market_update_available),
    LOCAL_NEWER(R.string.plugin_market_local_newer),
    UNKNOWN(R.string.plugin_market_version_unknown),
}

internal fun filterMarketPlugins(entries: List<PluginMarketEntry>, query: String): List<PluginMarketEntry> {
    val keyword = query.trim()
    return entries.filter { entry ->
        keyword.isEmpty() || entry.name.contains(keyword, true) ||
            entry.description.contains(keyword, true) || entry.tags.any { it.contains(keyword, true) }
    }
}

/** 只比较可确定顺序的纯数字版本；预发布和自定义版本不能用字典序猜测更新。 */
internal fun marketVersionState(remote: String, installed: String?): MarketVersionState {
    if (installed == null) return MarketVersionState.AVAILABLE
    fun parts(version: String): List<String>? {
        val clean = version.trim().removePrefix("v")
        if (!Regex("[0-9]+(\\.[0-9]+)*").matches(clean)) return null
        return clean.split('.').map { it.trimStart('0').ifEmpty { "0" } }
    }
    val latest = parts(remote) ?: return MarketVersionState.UNKNOWN
    val local = parts(installed) ?: return MarketVersionState.UNKNOWN
    for (index in 0 until maxOf(latest.size, local.size)) {
        val a = latest.getOrElse(index) { "0" }
        val b = local.getOrElse(index) { "0" }
        val comparison = a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
        if (comparison > 0) return MarketVersionState.UPDATE
        if (comparison < 0) return MarketVersionState.LOCAL_NEWER
    }
    return MarketVersionState.INSTALLED
}

internal fun marketHasDownload(entry: PluginMarketEntry): Boolean = entry.downloadUrl.isNotBlank()

/** 展示真实目标但不回显 URL 用户信息、查询凭据或 fragment。 */
internal fun marketDownloadDisplay(url: String): String? = runCatching {
    val uri = URI(url)
    if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return@runCatching null
    buildString {
        append(uri.scheme).append("://")
        if (uri.host.contains(':') && !uri.host.startsWith('[')) append('[').append(uri.host).append(']')
        else append(uri.host)
        if (uri.port != -1) append(':').append(uri.port)
        append(uri.rawPath.orEmpty())
    }
}.getOrNull()

@Composable
internal fun PluginMarketNotice(event: PluginEvent?) {
    if (event == null) return
    // 下载库异常可能带出带签名的 URL，市场错误不直接回显远端异常正文。
    Text(
        text = when (event) {
            is PluginEvent.Installed -> stringResource(R.string.plugin_event_installed, event.name, event.version)
            is PluginEvent.Failed -> stringResource(R.string.plugin_market_install_failed)
            PluginEvent.Completed -> stringResource(R.string.plugin_event_completed)
        },
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = if (event is PluginEvent.Failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginMarketPanel(
    viewModel: ExtensionsViewModel,
    anyBusy: Boolean,
    onInstall: (PluginMarketEntry, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by viewModel.marketPlugins.collectAsStateWithLifecycle()
    val loading by viewModel.marketLoading.collectAsStateWithLifecycle()
    val loaded by viewModel.marketLoaded.collectAsStateWithLifecycle()
    val failed by viewModel.marketFailed.collectAsStateWithLifecycle()
    val source by viewModel.marketSourceLabel.collectAsStateWithLifecycle()
    val installed by viewModel.installedVersions.collectAsStateWithLifecycle()
    val versionsReady by viewModel.installedVersionsReady.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var selectedIndex by remember(entries) { mutableStateOf<Int?>(null) }
    val visible = remember(entries, query) { filterMarketPlugins(entries, query) }
    val selected = selectedIndex?.let(entries::getOrNull)

    LaunchedEffect(Unit) { if (!viewModel.marketLoaded.value) viewModel.loadMarket() }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(stringResource(R.string.plugin_market_format_hint), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.plugin_market_source, source.ifBlank { stringResource(R.string.plugin_market_unknown) }),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        AppTextField(
            value = query,
            onValueChange = viewModel::search,
            placeholder = stringResource(R.string.plugin_market_search),
        )
        TextButton(onClick = { viewModel.refresh(); viewModel.loadMarket() }, enabled = !loading && !anyBusy) {
            Text(stringResource(if (failed) R.string.plugin_market_retry else R.string.plugin_market_refresh))
        }
        when {
            loading || !loaded -> CircularProgressIndicator()
            failed -> Text(stringResource(R.string.plugin_market_load_failed), color = MaterialTheme.colorScheme.error)
            entries.isEmpty() -> Text(stringResource(R.string.plugin_market_empty))
            visible.isEmpty() -> Text(stringResource(R.string.plugin_market_no_matches))
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                itemsIndexed(visible, key = { index, entry -> "${entry.id}:$index" }) { _, entry ->
                    SettingsGroup {
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { selectedIndex = entries.indexOfFirst { it === entry }.takeIf { it >= 0 } }.padding(Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Text(entry.name.ifBlank { entry.id }, style = MaterialTheme.typography.titleSmall)
                            if (versionsReady) MarketMetadata(entry, source, installed[entry.id])
                            else Text(stringResource(R.string.plugin_market_versions_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(entry.description.ifBlank { stringResource(R.string.plugin_market_no_description) },
                                style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            Text(stringResource(R.string.plugin_market_details), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        val state = marketVersionState(entry.version, installed[entry.id])
        val hasDownload = marketHasDownload(entry)
        val canInstall = versionsReady && hasDownload && state != MarketVersionState.INSTALLED && state != MarketVersionState.LOCAL_NEWER
        AdaptiveModalBottomSheet(onDismissRequest = { selectedIndex = null }) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(entry.name.ifBlank { entry.id }, style = MaterialTheme.typography.titleLarge)
                if (versionsReady) MarketMetadata(entry, source, installed[entry.id])
                else Text(stringResource(R.string.plugin_market_versions_unavailable), color = MaterialTheme.colorScheme.error)
                Text(entry.description.ifBlank { stringResource(R.string.plugin_market_no_description) })
                if (entry.tags.isNotEmpty()) Text(stringResource(R.string.plugin_market_tags, entry.tags.joinToString(" · ")))
                Text(stringResource(R.string.plugin_market_download,
                    marketDownloadDisplay(entry.downloadUrl) ?: stringResource(R.string.plugin_market_url_unavailable)))
                Text(stringResource(R.string.plugin_market_url_redacted), style = MaterialTheme.typography.bodySmall)
                if (!hasDownload) Text(stringResource(R.string.plugin_market_missing_download), color = MaterialTheme.colorScheme.error)
                if (state == MarketVersionState.UNKNOWN) {
                    Text(stringResource(R.string.plugin_market_unknown_warning), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.plugin_market_install_warning), color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(onClick = { selectedIndex = null }) { Text(stringResource(R.string.plugin_market_close)) }
                    Button(onClick = { onInstall(entry, state == MarketVersionState.UNKNOWN) }, enabled = canInstall && !anyBusy) {
                        Text(stringResource(if (state == MarketVersionState.UPDATE)
                            R.string.plugin_market_confirm_update else R.string.plugin_market_confirm_install))
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketMetadata(entry: PluginMarketEntry, source: String, installedVersion: String?) {
    val unknown = stringResource(R.string.plugin_market_unknown)
    Text(stringResource(R.string.plugin_market_metadata, entry.version.ifBlank { unknown }, entry.author.ifBlank { unknown }),
        style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.plugin_market_entry_source, source.ifBlank { unknown }, entry.repo.ifBlank { unknown }),
        style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.plugin_market_installed_version, installedVersion?.ifBlank { unknown }
        ?: stringResource(R.string.plugin_market_not_installed)), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(marketVersionState(entry.version, installedVersion).label),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
}
