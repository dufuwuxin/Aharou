package com.aharou.feature.settings.presentation.component

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Text(stringResource(R.string.account_provider_isolation_unavailable))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context -> WebView(context).apply {
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
                                return url.scheme !in setOf("https", "http") || url.scheme == "http" && url.host != "127.0.0.1"
                            }
                        }
                        loadUrl(login.url)
                    } },
                    onRelease = { it.stopLoading(); it.destroy() },
                )
            }
        }
    }
}
