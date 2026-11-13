package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
        AccountProviderKind.TRAE_GLOBAL -> R.string.provider_account_trae_global
        AccountProviderKind.WORKBUDDY_GLOBAL -> R.string.provider_account_workbuddy_global
        AccountProviderKind.LOOMY -> R.string.provider_account_loomy
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
    var accounts by remember(providerId, kind) { mutableStateOf(emptyList<com.aharou.feature.settings.data.account.AccountPoolEntry>()) }
    var snapshot by remember(providerId, kind) { mutableStateOf<AccountSnapshot?>(null) }
    var login by remember(providerId, kind) { mutableStateOf<AccountLogin?>(null) }
    var operation by remember(providerId, kind) { mutableStateOf<Job?>(null) }
    var busy by remember(providerId, kind) { mutableStateOf(false) }
    var error by remember(providerId, kind) { mutableStateOf<String?>(null) }
    var session by remember(providerId, kind) { mutableStateOf("") }
    var checkInStatus by remember(providerId, kind) { mutableStateOf<com.aharou.feature.settings.data.account.AccountCheckInStatus?>(null) }
    val modelsCallback = androidx.compose.runtime.rememberUpdatedState(onModels)

    suspend fun refresh() {
        val version = manager.accountVersion(providerId)
        val result = manager.snapshot(providerId, kind)
        manager.requireAccountVersion(providerId, version)
        val ids = result.models.map { it.id }
        val current = repository.getProviderById(providerId) ?: providerState.value
        manager.requireAccountVersion(providerId, version)
        manager.persistSnapshot(providerId, version, repository, current.copy(models = ids,
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
                withContext(kotlinx.coroutines.NonCancellable) {
                    accounts = withContext(Dispatchers.IO) { manager.accounts(providerId, kind) }
                    loggedIn = accounts.isNotEmpty()
                }
            }
        }
    }
    LaunchedEffect(providerId, kind) {
        accounts = withContext(Dispatchers.IO) { manager.accounts(providerId, kind) }
        loggedIn = accounts.isNotEmpty()
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
        if (kind == AccountProviderKind.LOOMY) {
            com.aharou.core.ui.AppTextField(
                value = session,
                onValueChange = { session = it },
                label = stringResource(R.string.provider_account_loomy_session),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                enabled = !busy,
                singleLine = true,
            )
            Text(stringResource(R.string.provider_account_loomy_import_help), modifier = Modifier.padding(Spacing.md), style = MaterialTheme.typography.bodySmall)
        }
        SettingsRow(
            title = accountProviderName(kind),
            subtitle = stringResource(if (loggedIn) R.string.provider_account_signed_in else R.string.provider_account_signed_out),
            trailing = {
                TextButton(enabled = !busy, onClick = {
                    launchOperation {
                        repository.saveProvider(providerState.value)
                        if (kind == AccountProviderKind.LOOMY) {
                            val imported = session
                            session = ""
                            manager.importLoomySession(providerId, imported)
                            loggedIn = true
                            snapshot = null
                            refresh()
                            return@launchOperation
                        }
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
                }) { Text(stringResource(if (kind == AccountProviderKind.LOOMY) R.string.provider_account_import_session else if (android.os.Build.VERSION.SDK_INT >= 28) R.string.account_provider_add_account else R.string.account_provider_add_account_legacy)) }
            },
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.provider_account_points),
            subtitle = if (snapshot?.balanceError != null) stringResource(R.string.provider_account_balance_failed)
                else snapshot?.balance ?: stringResource(R.string.provider_account_unknown),
            trailing = {
                TextButton(enabled = loggedIn && !busy, onClick = { launchOperation { refresh() } }) {
                    Text(stringResource(if (busy) R.string.provider_account_loading else R.string.provider_account_refresh))
                }
            },
        )
        SettingsRow(
            title = stringResource(R.string.provider_account_check_in),
            subtitle = stringResource(when (checkInStatus) {
                com.aharou.feature.settings.data.account.AccountCheckInStatus.CLAIMED -> R.string.provider_account_check_in_claimed
                com.aharou.feature.settings.data.account.AccountCheckInStatus.ALREADY_CHECKED -> R.string.provider_account_check_in_already
                com.aharou.feature.settings.data.account.AccountCheckInStatus.UNKNOWN -> R.string.provider_account_check_in_unknown
                com.aharou.feature.settings.data.account.AccountCheckInStatus.UNSUPPORTED -> R.string.provider_account_check_in_unsupported
                null -> R.string.provider_account_check_in_help
            }),
            trailing = {
                TextButton(enabled = loggedIn && !busy, onClick = {
                    launchOperation {
                        checkInStatus = null
                        val result = manager.checkIn(providerId, kind)
                        checkInStatus = result.status
                        if (result.status != com.aharou.feature.settings.data.account.AccountCheckInStatus.UNSUPPORTED) refresh()
                    }
                }) { Text(stringResource(R.string.provider_account_check_in)) }
            },
        )
        accounts.forEach { slot ->
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.account_provider_slot, slot.position),
                trailing = {
                    Row {
                        TextButton(enabled = !busy, onClick = {
                            launchOperation { checkInStatus = manager.checkInAccount(providerId, kind, slot.accountId).status }
                        }) { Text(stringResource(R.string.provider_account_check_in)) }
                        TextButton(enabled = !busy, onClick = {
                            launchOperation {
                                withContext(Dispatchers.IO) { manager.removeAccount(providerId, kind, slot.accountId) }
                                snapshot = null
                                if (manager.isLoggedIn(providerId)) refresh() else {
                                    val version = manager.accountVersion(providerId)
                                    val current = repository.getProviderById(providerId) ?: providerState.value
                                    manager.persistSnapshot(providerId, version, repository, current.copy(models = emptyList(), defaultModel = "", selectedModel = ""))
                                    modelsCallback.value(emptyList())
                                }
                            }
                        }) { Text(stringResource(R.string.account_provider_remove_account)) }
                    }
                },
            )
        }
        Text(stringResource(R.string.account_provider_pool_hint), modifier = Modifier.padding(Spacing.md), style = MaterialTheme.typography.bodySmall)
        snapshot?.let { current ->
            if (current.accountIssues.isNotEmpty()) Text(stringResource(R.string.account_provider_partial), modifier = Modifier.padding(Spacing.md), color = MaterialTheme.colorScheme.error)
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
                        "isolated_login_requires_api28" -> R.string.account_provider_isolation_unavailable
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
                        checkInStatus = null
                        session = ""
                        loggedIn = false
                        accounts = emptyList()
                        modelsCallback.value(emptyList())
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
    if (android.os.Build.VERSION.SDK_INT < 28) {
        AccountLegacyLoginDialog(login, manager, onDismiss)
        return
    }
    val context = LocalContext.current
    DisposableEffect(login) {
        var remote: android.os.ResultReceiver? = null
        var disposed = false
        val receiver = object : android.os.ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
            @Suppress("DEPRECATION")
            override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) {
                when (resultCode) {
                    AccountLoginActivity.READY -> {
                        remote = resultData?.getParcelable(AccountLoginActivity.RECEIVER)
                        if (disposed) remote?.send(0, android.os.Bundle.EMPTY)
                    }
                    AccountLoginActivity.CALLBACK -> {
                        val url = resultData?.getString(AccountLoginActivity.URL) ?: return
                        if (!disposed && !manager.acceptCallback(login, url)) onDismiss()
                    }
                    AccountLoginActivity.CANCELLED -> if (!disposed) onDismiss()
                }
            }
        }
        context.startActivity(android.content.Intent(context, AccountLoginActivity::class.java).apply {
            putExtra(AccountLoginActivity.URL, login.url)
            putExtra(AccountLoginActivity.CALLBACK_PREFIX, login.callbackPrefix)
            putExtra(AccountLoginActivity.RECEIVER, receiver)
        })
        onDispose { disposed = true; remote?.send(0, android.os.Bundle.EMPTY) }
    }
}
