package com.aharou.feature.settings.presentation.component

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.settings.data.account.AccountLogin
import com.aharou.feature.settings.data.account.AccountProviderException
import com.aharou.feature.settings.data.account.AccountProviderKind
import com.aharou.feature.settings.data.account.AccountProviderManager
import com.aharou.feature.settings.data.account.AccountSnapshot
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AccountProviderEntryPoint {
    fun accountProviderManager(): AccountProviderManager
    fun providerRepository(): com.aharou.feature.settings.domain.repository.AIProviderRepository
}

@Composable
internal fun rememberAccountProviderManager(): AccountProviderManager {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors.fromApplication(context, AccountProviderEntryPoint::class.java).accountProviderManager()
    }
}

@Composable
internal fun accountProviderName(kind: AccountProviderKind): String = stringResource(
    when (kind) {
        AccountProviderKind.TRAE -> R.string.provider_account_trae
        AccountProviderKind.WORKBUDDY -> R.string.provider_account_workbuddy
        AccountProviderKind.RACCOON -> R.string.provider_account_raccoon
    }
)

@Composable
internal fun ProviderAccountGroup(
    manager: AccountProviderManager,
    provider: com.aharou.feature.settings.domain.model.AIProviderConfig,
    kind: AccountProviderKind,
    onModels: (List<String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providerId = provider.id
    val providerState = androidx.compose.runtime.rememberUpdatedState(provider)
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { EntryPointAccessors.fromApplication(context, AccountProviderEntryPoint::class.java).providerRepository() }
    var loggedIn by remember(providerId, kind) { mutableStateOf(false) }
    var snapshot by remember(providerId, kind) { mutableStateOf<AccountSnapshot?>(null) }
    var login by remember(providerId, kind) { mutableStateOf<AccountLogin?>(null) }
    var operation by remember(providerId, kind) { mutableStateOf<Job?>(null) }
    var busy by remember(providerId, kind) { mutableStateOf(false) }
    var error by remember(providerId, kind) { mutableStateOf<String?>(null) }
    val modelsCallback = androidx.compose.runtime.rememberUpdatedState(onModels)

    suspend fun refresh() {
        val version = manager.accountVersion(providerId)
        val result = manager.snapshot(providerId, kind)
        manager.requireAccountVersion(providerId, version)
        val ids = result.models.map { it.id }
        val current = repository.getProviderById(providerId) ?: providerState.value
        manager.requireAccountVersion(providerId, version)
        repository.saveProvider(current.copy(models = ids,
            defaultModel = current.defaultModel.takeIf { it in ids } ?: ids.firstOrNull().orEmpty(),
            selectedModel = current.selectedModel.takeIf { it in ids } ?: ids.firstOrNull().orEmpty()))
        manager.requireAccountVersion(providerId, version)
        snapshot = result
        modelsCallback.value(ids)
    }
    fun launchOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        operation = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = (failure as? AccountProviderException)?.errorCode ?: "network_error"
            } finally {
                busy = false
                loggedIn = withContext(Dispatchers.IO) { manager.isLoggedIn(providerId) }
            }
        }
    }
    LaunchedEffect(providerId, kind) {
        loggedIn = withContext(Dispatchers.IO) { manager.isLoggedIn(providerId) }
        if (loggedIn) launchOperation { refresh() }
    }
    DisposableEffect(providerId, kind) {
        onDispose {
            operation?.cancel()
            login?.let(manager::cancelLogin)
        }
    }

    SettingsGroupHeader(text = stringResource(R.string.provider_account_title))
    SettingsGroup {
        SettingsRow(
            title = accountProviderName(kind),
            subtitle = stringResource(if (loggedIn) R.string.provider_account_signed_in else R.string.provider_account_signed_out),
            trailing = {
                TextButton(enabled = !busy, onClick = {
                    launchOperation {
                        repository.saveProvider(providerState.value)
                        val attempt = manager.beginLogin(providerId, kind)
                        login = attempt
                        try {
                            manager.awaitLogin(attempt)
                            loggedIn = true
                            login = null
                            snapshot = null
                            refresh()
                        } finally {
                            manager.cancelLogin(attempt)
                            login = null
                        }
                    }
                }) { Text(stringResource(if (loggedIn) R.string.provider_account_sign_in_again else R.string.provider_account_sign_in)) }
            },
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.provider_account_points),
            subtitle = snapshot?.balance ?: stringResource(R.string.provider_account_unknown),
            trailing = {
                TextButton(enabled = loggedIn && !busy, onClick = { launchOperation { refresh() } }) {
                    Text(stringResource(if (busy) R.string.provider_account_loading else R.string.provider_account_refresh))
                }
            },
        )
        snapshot?.let { current ->
            Text(
                stringResource(R.string.provider_account_updated, DateFormat.getDateTimeInstance().format(Date(current.fetchedAt))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            )
            current.models.forEach { model ->
                SettingsDivider()
                SettingsRow(
                    title = model.name,
                    subtitle = model.id,
                    trailing = {
                        Text(
                            text = model.multiplier?.let { stringResource(R.string.provider_account_multiplier, it) }
                                ?: stringResource(R.string.provider_account_multiplier_unknown),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
        }
        error?.let { code ->
            Text(
                text = stringResource(
                    when (code) {
                        "login_required", "account_mismatch", "invalid_credentials" -> R.string.provider_account_auth_failed
                        "login_expired", "invalid_login" -> R.string.provider_account_login_failed
                        "account_changed" -> R.string.provider_account_changed
                        "account_restricted" -> R.string.provider_account_restricted
                        "channel_rejected" -> R.string.provider_account_channel_rejected
                        "quota_exhausted" -> R.string.provider_account_exhausted
                        "rate_limited" -> R.string.provider_account_rate_limited
                        else -> R.string.provider_account_request_failed
                    }
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(Spacing.md),
            )
        }
        Text(
            stringResource(R.string.provider_account_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Spacing.md),
        )
        if (loggedIn) {
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.provider_account_sign_out),
                subtitle = stringResource(R.string.provider_account_sign_out_hint),
                onClick = {
                    launchOperation {
                        withContext(Dispatchers.IO) { manager.logout(providerId) }
                        snapshot = null
                        loggedIn = false
                    }
                },
            )
        }
    }
    login?.let { attempt ->
        AccountLoginDialog(attempt, manager) {
            operation?.cancel()
            manager.cancelLogin(attempt)
            login = null
        }
    }
}

@Composable
private fun AccountLoginDialog(login: AccountLogin, manager: AccountProviderManager, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.provider_account_login_title), modifier = Modifier.padding(vertical = Spacing.md))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.safeBrowsingEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url
                                    if (manager.acceptCallback(login, url.toString())) return true
                                    return url.scheme !in setOf("https", "http") || (url.scheme == "http" && url.host != "127.0.0.1")
                                }
                            }
                            loadUrl(login.url)
                        }
                    },
                    onRelease = { webView -> webView.stopLoading(); webView.destroy() },
                )
            }
        }
    }
}
