package com.aharou.feature.settings.presentation.component

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Button
import com.aharou.R

/** No manager or credentials live in this process; the main-process receiver owns the login. */
class AccountLoginActivity : Activity() {
    private var browser: WebView? = null
    private var receiver: ResultReceiver? = null
    private var callbackSent = false
    private var closingFromOwner = false
    private var fresh = false
    private var windowToken = 0L

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = intent.getParcelableExtra(RECEIVER)
        val url = intent.getStringExtra(URL)
        // A recreated process must never reopen an authorization URL with a stale owner.
        if (Build.VERSION.SDK_INT < 28 || savedInstanceState != null || receiver == null || url == null) {
            finish()
            return
        }
        val uri = android.net.Uri.parse(url)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) { finish(); return }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        windowToken = windows.begin {
            closingFromOwner = true
            browser?.stopLoading()
            browser?.destroy()
            browser = null
            finish()
        }
        if (!profileConfigured) {
            WebView.setDataDirectorySuffix("account_login")
            profileConfigured = true
        }
        fresh = true
        // This CookieManager belongs exclusively to :account_login's suffixed profile.
        CookieManager.getInstance().removeAllCookies {
            if (!windows.isCurrent(windowToken) || isFinishing || isDestroyed) return@removeAllCookies
            CookieManager.getInstance().flush()
            showLogin(url)
        }
        receiver?.send(READY, Bundle().apply {
            putParcelable(RECEIVER, object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    closingFromOwner = true
                    finish()
                }
            }.forAccountLoginIpc())
        })
    }

    private fun showLogin(url: String) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(Button(this).apply {
            setText(R.string.common_close)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val callback = intent.getStringExtra(CALLBACK_PREFIX)
        browser = WebView(this).apply {
            clearHistory()
            clearCache(true)
            android.webkit.WebStorage.getInstance().deleteAllData()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (request.isForMainFrame && callback != null && target.startsWith("$callback?")) {
                        receiver?.send(CALLBACK, Bundle().apply { putString(URL, target) })
                        callbackSent = true
                        finish()
                        return true
                    }
                    return request.url.scheme != "https"
                }
            }
            loadUrl(url)
        }
        layout.addView(browser, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
    }

    override fun onDestroy() {
        if (!closingFromOwner && !callbackSent) receiver?.send(CANCELLED, Bundle.EMPTY)
        browser?.stopLoading()
        browser?.clearHistory()
        if (windows.isCurrent(windowToken)) browser?.clearCache(true)
        browser?.destroy()
        browser = null
        super.onDestroy()
        if (fresh) windows.close(windowToken) {
            android.webkit.WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        }
    }

    companion object {
        private var profileConfigured = false
        private val windows = AccountLoginWindowCoordinator()
        internal const val URL = "account_login_url"
        internal const val CALLBACK_PREFIX = "account_login_callback"
        internal const val RECEIVER = "account_login_receiver"
        internal const val READY = 1
        internal const val CALLBACK = 2
        internal const val CANCELLED = 3
    }
}
