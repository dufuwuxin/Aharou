package com.aharou.feature.settings.presentation.component

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import com.aharou.R

/** No manager or credentials live in this process; the main-process receiver owns the login. */
class AccountLoginActivity : Activity() {
    private var browser: WebView? = null
    private var status: TextView? = null
    private var loading = true
    private var awaitingReply = false
    private var submission = 0L
    private var receiver: ResultReceiver? = null
    private var callbackSent = false
    private var closingFromOwner = false
    private var fresh = false
    private var windowToken = 0L
    private var webCookieLogin = false
    private var webKind: com.aharou.feature.settings.data.account.AccountProviderKind? = null

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
        webCookieLogin = intent.getBooleanExtra(WEB_COOKIE_LOGIN, false)
        webKind = com.aharou.feature.settings.data.account.AccountProviderKind.fromWireId(intent.getStringExtra(LOGIN_KIND).orEmpty())
            ?: if (webCookieLogin) com.aharou.feature.settings.data.account.AccountProviderKind.DUMATE else null
        if (webCookieLogin) {
            val expected = when (webKind) {
                com.aharou.feature.settings.data.account.AccountProviderKind.DUMATE -> com.aharou.feature.settings.data.account.DumateAccountProtocol.LOGIN
                com.aharou.feature.settings.data.account.AccountProviderKind.DOUBAO -> com.aharou.feature.settings.data.account.DoubaoAccountProtocol.LOGIN
                else -> null
            }
            if (expected == null || url != expected) { finish(); return }
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
                    if (resultCode == RESULT_ACK) {
                        awaitingReply = false
                        if (resultData?.getBoolean(ACCEPTED) == true) {
                            callbackSent = true
                            finish()
                        } else feedback(R.string.account_login_rejected)
                    } else {
                        closingFromOwner = true
                        finish()
                    }
                }
            }.forAccountLoginIpc())
        })
    }

    private fun feedback(message: Int) { status?.setText(message) }

    private fun submit(code: Int, data: Bundle) {
        val owner = receiver
        if (owner == null) { feedback(R.string.account_login_rejected); return }
        awaitingReply = true
        val attempt = ++submission
        feedback(R.string.account_login_validating)
        try { owner.send(code, data) } catch (_: RuntimeException) {
            awaitingReply = false
            feedback(R.string.account_login_rejected)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (awaitingReply && submission == attempt && windows.isCurrent(windowToken) && !isFinishing && !isDestroyed) {
                awaitingReply = false
                feedback(R.string.account_login_rejected)
            }
        }, 8000L)
    }

    private fun navigate(target: String): Boolean {
        if (AccountLoginWebPolicy.isCallback(target, intent.getStringExtra(CALLBACK_PREFIX))) {
            if (!awaitingReply && !callbackSent && windows.isCurrent(windowToken)) {
                submit(CALLBACK, Bundle().apply { putString(URL, target) })
            }
            return true
        }
        if (AccountLoginWebPolicy.isHttps(target)) return false
        feedback(R.string.account_login_unsupported_link)
        return true
    }

    private fun showLogin(url: String) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(Button(this).apply {
            setText(R.string.common_close)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply { setText(R.string.account_login_loading) }
        layout.addView(status)
        if (webCookieLogin) layout.addView(Button(this).apply {
            setText(R.string.provider_account_dumate_complete)
            setOnClickListener {
                if (!windows.isCurrent(this@AccountLoginActivity.windowToken) || callbackSent) {
                    feedback(R.string.account_login_rejected); return@setOnClickListener
                }
                if (awaitingReply) { feedback(R.string.account_login_validating); return@setOnClickListener }
                if (loading) { feedback(R.string.account_login_loading); return@setOnClickListener }
                val current = browser?.url.orEmpty()
                val doubao = webKind == com.aharou.feature.settings.data.account.AccountProviderKind.DOUBAO
                val origin = if (doubao) com.aharou.feature.settings.data.account.DoubaoAccountProtocol.WEB
                    else com.aharou.feature.settings.data.account.DumateAccountProtocol.WEB
                val valid = if (doubao) com.aharou.feature.settings.data.account.DoubaoAccountProtocol.isWebOrigin(current)
                    else com.aharou.feature.settings.data.account.DumateAccountProtocol.isWebOrigin(current)
                if (!valid) { feedback(R.string.account_login_wrong_page); return@setOnClickListener }
                val cookieUrl = if (doubao) com.aharou.feature.settings.data.account.DoubaoAccountProtocol.LOGIN else com.aharou.feature.settings.data.account.DumateAccountProtocol.LOGIN
                val cookie = CookieManager.getInstance().getCookie(cookieUrl).orEmpty()
                try {
                    if (doubao) com.aharou.feature.settings.data.account.DoubaoAccountProtocol.requireCookie(cookie)
                    else com.aharou.feature.settings.data.account.DumateAccountProtocol.requireCookie(cookie)
                } catch (_: com.aharou.feature.settings.data.account.AccountProviderException) {
                    feedback(R.string.account_login_missing_cookie); return@setOnClickListener
                }
                val loginId = intent.getStringExtra(LOGIN_ID)
                val state = intent.getStringExtra(LOGIN_STATE)
                if (loginId == null || state == null) { feedback(R.string.account_login_rejected); return@setOnClickListener }
                submit(WEB_SESSION, Bundle().apply {
                    putString(ORIGIN, origin)
                    putString(LOGIN_KIND, webKind?.wireId)
                    putString(LOGIN_ID, loginId)
                    putString(LOGIN_STATE, state)
                    putString(WEB_COOKIE, cookie)
                })
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        browser = WebView(this).apply {
            clearHistory()
            clearCache(true)
            android.webkit.WebStorage.getInstance().deleteAllData()
            AccountLoginWebPolicy.configure(this, ::navigate, { active ->
                loading = active
                if (!awaitingReply) feedback(if (active) R.string.account_login_loading else R.string.account_login_ready)
            }, { feedback(R.string.account_login_page_failed) })
            loadUrl(url)
        }
        layout.addView(browser, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        AccountLoginWebPolicy.applyInsets(layout)
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
        internal const val WEB_SESSION = 4
        internal const val RESULT_ACK = 5
        internal const val ACCEPTED = "account_login_accepted"
        internal const val WEB_COOKIE_LOGIN = "account_web_cookie_login"
        internal const val WEB_COOKIE = "account_web_cookie"
        internal const val ORIGIN = "account_web_origin"
        internal const val LOGIN_KIND = "account_login_kind"
        internal const val LOGIN_ID = "account_login_id"
        internal const val LOGIN_STATE = "account_login_state"
    }
}
