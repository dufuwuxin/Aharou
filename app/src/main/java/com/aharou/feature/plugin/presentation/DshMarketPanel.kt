package com.aharou.feature.plugin.presentation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.domain.market.PluginMarketplaceActions
import com.aharou.feature.settings.presentation.component.SettingsGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal data class DshMarketState(
    val query: String = "", val page: Int = 1, val busy: Boolean = false,
    val items: List<JsonObject> = emptyList(), val notice: Int? = null, val diagnostic: String? = null,
    val transport: String? = null,
)

@HiltViewModel
internal class DshMarketViewModel @Inject constructor(private val actions: PluginMarketplaceActions) : ViewModel() {
    private val mutable = MutableStateFlow(DshMarketState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var pending: Pair<String, Int>? = null
    init { load(0) }
    fun query(value: String) { mutable.update { it.copy(query = value) } }
    fun load(offset: Int) {
        if (mutable.value.busy) return
        val request = mutable.value.copy(page = (mutable.value.page + offset).coerceAtLeast(1))
        mutable.value = request.copy(busy = true, notice = null, diagnostic = null, items = emptyList(), transport = null)
        operation = viewModelScope.launch {
            try {
                val result = actions.query("dsh", request.query, request.page)
                mutable.update { it.copy(items = result.getValue("items").jsonArray.map { item -> item.jsonObject },
                    transport = result["transport"]?.jsonPrimitive?.contentOrNull) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val unavailable = failure.message.orEmpty().contains("dsh_container_unavailable")
                mutable.update { it.copy(notice = if (unavailable) R.string.dsh_market_container_unavailable else R.string.dsh_market_failed,
                    diagnostic = diagnostic(failure, "catalog")) }
            }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun chooseDownload(id: String) { pending = id to mutable.value.page }
    fun download(uri: Uri?) {
        val choice = pending ?: return
        pending = null
        if (uri != null) perform("download", choice.first, choice.second, uri)
    }
    fun install(id: String) = perform("install", id, mutable.value.page, null)
    private fun perform(action: String, id: String, page: Int, document: Uri?) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, notice = null, diagnostic = null) }
        operation = viewModelScope.launch {
            try {
                actions.perform(action, "dsh", id, page, document = document)
                mutable.update { it.copy(notice = if (action == "install") R.string.dsh_market_installed else R.string.dsh_market_downloaded) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val notice = when (PluginMarketplaceActions.failureCode(failure)) {
                    "UNSUPPORTED_FORMAT" -> R.string.dsh_market_scope
                    "NAME_CONFLICT" -> R.string.skills_import_error_name_conflict
                    "DOWNLOAD_FAILED" -> R.string.dsh_market_failed
                    "SKILL_IMPORT_FAILED" -> R.string.operit_import_failed
                    else -> if (action == "install") R.string.plugin_market_install_failed else R.string.dsh_market_failed
                }
                mutable.update { it.copy(notice = notice, diagnostic = diagnostic(failure, if (action == "install") "install" else "source_download")) }
            }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun diagnostic(failure: Exception, fallback: String): String {
        val chain = generateSequence<Throwable>(failure) { it.cause }.take(12).toList()
        val stage = chain.firstNotNullOfOrNull {
            Regex("dsh_catalog_transport:(tls|dns|timeout|network|response)|dsh_catalog_parse")
                .find(it.message.orEmpty())?.value
        } ?: fallback
        val http = chain.firstNotNullOfOrNull { Regex("market_http_failure:[0-9]{3}").find(it.message.orEmpty())?.value }
        val container = chain.firstNotNullOfOrNull {
            Regex("dsh_container_[a-z_]+(?::[0-9]+)?").find(it.message.orEmpty())?.value
        }
        return listOfNotNull(stage.take(80), http, container).joinToString(" / ")
    }
    fun cancel() { operation?.cancel() }
}

@Composable
internal fun DshMarketPanel(viewModel: DshMarketViewModel, externalBusy: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), viewModel::download)
    var detail by remember { mutableStateOf<String?>(null) }
    val busy = state.busy || externalBusy
    Column(modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.dsh_market_scope), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dpharness.com/plugins"))) }) {
            Text(stringResource(R.string.dsh_market_open))
        }
        AppTextField(value = state.query, onValueChange = viewModel::query, placeholder = stringResource(R.string.operit_search))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextButton(onClick = { viewModel.load(-1) }, enabled = !busy && state.page > 1) { Text(stringResource(R.string.operit_previous)) }
            TextButton(onClick = { viewModel.load(0) }, enabled = !busy) { Text(stringResource(R.string.plugin_market_retry)) }
            TextButton(onClick = { viewModel.load(1) }, enabled = !busy) { Text(stringResource(R.string.operit_next)) }
        }
        Text(stringResource(R.string.dsh_market_page, state.page))
        if (state.transport == "container") {
            Text(stringResource(R.string.dsh_market_transport_container), style = MaterialTheme.typography.bodySmall)
        }
        state.notice?.let { notice ->
            val diagnostic = state.diagnostic.orEmpty()
            val message = when {
                notice == R.string.dsh_market_container_unavailable -> notice
                "transport:tls" in diagnostic -> R.string.dsh_market_tls_failed
                "transport:dns" in diagnostic -> R.string.dsh_market_dns_failed
                "transport:timeout" in diagnostic -> R.string.dsh_market_timeout
                "dsh_catalog_parse" in diagnostic -> R.string.dsh_market_parse_failed
                else -> notice
            }
            Text(stringResource(message) + diagnostic.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty(),
                color = MaterialTheme.colorScheme.primary)
        }
        if (state.busy) {
            CircularProgressIndicator()
            TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.plugin_cancel)) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(state.items, key = { it.getValue("fullName").jsonPrimitive.content }) { entry ->
                val id = entry.getValue("fullName").jsonPrimitive.content
                SettingsGroup {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(entry["nameZh"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: id, style = MaterialTheme.typography.titleMedium)
                        Text(id, style = MaterialTheme.typography.labelSmall)
                        Text(entry["descriptionZh"]?.jsonPrimitive?.contentOrNull ?: entry["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        TextButton(onClick = { detail = if (detail == id) null else id }) { Text(stringResource(R.string.dsh_market_details)) }
                        if (detail == id) Text(entry.toString(), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            TextButton(enabled = !busy, onClick = {
                                viewModel.chooseDownload(id)
                                launcher.launch(id.replace('/', '-') + ".zip")
                            }) { Text(stringResource(R.string.dsh_market_download)) }
                            TextButton(enabled = !busy, onClick = { viewModel.install(id) }) { Text(stringResource(R.string.dsh_market_install)) }
                        }
                    }
                }
            }
        }
    }
}
