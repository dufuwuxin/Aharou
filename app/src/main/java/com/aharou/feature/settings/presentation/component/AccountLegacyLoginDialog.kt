package com.aharou.feature.settings.presentation.component

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aharou.R
import com.aharou.feature.settings.data.account.AccountLogin
import com.aharou.feature.settings.data.account.AccountProviderManager

@Composable
internal fun AccountLegacyLoginDialog(login: AccountLogin, manager: AccountProviderManager, onDismiss: () -> Unit) {
    var feedback by remember(login) { mutableStateOf(R.string.account_login_loading) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Text(stringResource(R.string.account_provider_isolation_unavailable))
                Text(stringResource(feedback))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context -> WebView(context).apply {
                        AccountLoginWebPolicy.configure(this, { target ->
                            if (AccountLoginWebPolicy.isCallback(target, login.callbackPrefix)) {
                                if (!manager.acceptCallback(login, target)) feedback = R.string.account_login_rejected
                                else feedback = R.string.account_login_validating
                                true
                            } else if (AccountLoginWebPolicy.isHttps(target)) false
                            else { feedback = R.string.account_login_unsupported_link; true }
                        }, { active -> feedback = if (active) R.string.account_login_loading else R.string.account_login_ready },
                            { feedback = R.string.account_login_page_failed })
                        loadUrl(login.url)
                    } },
                    onRelease = { it.stopLoading(); it.destroy() },
                )
            }
        }
    }
}
