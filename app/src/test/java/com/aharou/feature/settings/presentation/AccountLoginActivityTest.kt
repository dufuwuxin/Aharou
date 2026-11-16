package com.aharou.feature.settings.presentation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.ResultReceiver
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aharou.R
import com.aharou.feature.settings.data.account.AccountProviderKind
import com.aharou.feature.settings.data.account.DoubaoAccountProtocol
import com.aharou.feature.settings.presentation.component.AccountLoginActivity
import com.aharou.feature.settings.presentation.component.AccountLoginWebPolicy
import com.aharou.feature.settings.presentation.component.AccountLoginWindowCoordinator
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class AccountLoginActivityTest {
    @Test
    @Config(sdk = [26, 27, 28, 35])
    fun sharedWebViewActuallyAppliesDesktopSettingsForBothWindowPaths() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val browser = WebView(controller.get())
        try {
            AccountLoginWebPolicy.configure(browser, { false }, {}, {})
            assertEquals(AccountLoginWebPolicy.DESKTOP_USER_AGENT, browser.settings.userAgentString)
            assertTrue(browser.settings.useWideViewPort)
            assertTrue(browser.settings.loadWithOverviewMode)
            assertTrue(browser.settings.supportMultipleWindows())
            assertFalse(browser.settings.allowFileAccess)
            assertFalse(browser.settings.allowContentAccess)
        } finally { browser.destroy(); controller.pause().stop().destroy() }
    }

    @Test fun doubaoCompleteButtonSendsPathCookieAndIdentityWithoutClosingBeforeAcknowledgement() {
        withDoubaoLogin { activity, browser, complete, status, events ->
            browser.loadUrl("${DoubaoAccountProtocol.WEB}/chat/conversation?foo=bar")
            browser.webViewClient.onPageFinished(browser, browser.url)
            CookieManager.getInstance().setCookie(DoubaoAccountProtocol.LOGIN,
                "sessionid=fixture-session; Path=/chat; Secure")

            assertTrue(complete.performClick())

            assertEquals(1, events.size)
            val (code, data) = events.single()
            assertEquals(AccountLoginActivity.WEB_SESSION, code)
            assertEquals(DoubaoAccountProtocol.WEB, data.getString(AccountLoginActivity.ORIGIN))
            assertEquals(AccountProviderKind.DOUBAO.wireId, data.getString(AccountLoginActivity.LOGIN_KIND))
            assertEquals("fixture-provider", data.getString(AccountLoginActivity.LOGIN_ID))
            assertEquals("fixture-state", data.getString(AccountLoginActivity.LOGIN_STATE))
            assertEquals("sessionid=fixture-session", data.getString(AccountLoginActivity.WEB_COOKIE))
            assertFalse(data.containsKey(AccountLoginActivity.URL))
            assertEquals(activity.getString(R.string.account_login_validating), status.text.toString())
            assertFalse(activity.isFinishing)
        }
    }

    @Test fun doubaoCompleteButtonShowsMissingCookieAndWrongPageWithoutSending() {
        withDoubaoLogin { activity, browser, complete, status, events ->
            browser.loadUrl("${DoubaoAccountProtocol.WEB}/chat/conversation?foo=bar")
            browser.webViewClient.onPageFinished(browser, browser.url)
            assertTrue(complete.performClick())
            assertEquals(activity.getString(R.string.account_login_missing_cookie), status.text.toString())
            assertTrue(events.isEmpty())

            CookieManager.getInstance().setCookie(DoubaoAccountProtocol.LOGIN,
                "sessionid=fixture-session; Path=/chat; Secure")
            browser.loadUrl("https://example.invalid/chat/conversation?foo=bar")
            browser.webViewClient.onPageFinished(browser, browser.url)
            assertTrue(complete.performClick())
            assertEquals(activity.getString(R.string.account_login_wrong_page), status.text.toString())
            assertTrue(events.isEmpty())
            assertFalse(activity.isFinishing)
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun doubaoAcknowledgementTimeoutShowsFailureAndAllowsAnotherRealClick() {
        withDoubaoLogin { activity, browser, complete, status, events ->
            browser.loadUrl("${DoubaoAccountProtocol.WEB}/chat/conversation?foo=bar")
            browser.webViewClient.onPageFinished(browser, browser.url)
            CookieManager.getInstance().setCookie(DoubaoAccountProtocol.LOGIN,
                "sessionid=fixture-session; Path=/chat; Secure")
            assertTrue(complete.performClick())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(7999))
            assertEquals(activity.getString(R.string.account_login_validating), status.text.toString())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(activity.getString(R.string.account_login_rejected), status.text.toString())
            assertFalse(activity.isFinishing)

            assertTrue(complete.performClick())
            assertEquals(2, events.size)
            assertTrue(events.all { it.first == AccountLoginActivity.WEB_SESSION })
            assertEquals(activity.getString(R.string.account_login_validating), status.text.toString())
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun receivedSubmissionWaitsForPersistenceAndFailedValidationAllowsRetryInSameBrowser() {
        withDoubaoLogin { activity, browser, complete, status, events ->
            browser.loadUrl(DoubaoAccountProtocol.LOGIN)
            browser.webViewClient.onPageFinished(browser, browser.url)
            CookieManager.getInstance().setCookie(DoubaoAccountProtocol.LOGIN, "sessionid=fixture-session; Path=/chat; Secure")
            complete.performClick()
            activity.receiveOwnerResult(AccountLoginActivity.RESULT_PENDING, Bundle.EMPTY)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
            assertEquals(activity.getString(R.string.account_login_validating), status.text.toString())
            assertFalse(activity.isFinishing)
            activity.receiveOwnerResult(AccountLoginActivity.RESULT_ACK, Bundle().apply {
                putBoolean(AccountLoginActivity.ACCEPTED, false)
                putBoolean(AccountLoginActivity.RETRY_ALLOWED, true)
            })
            assertEquals(activity.getString(R.string.account_login_retry_validation), status.text.toString())
            assertFalse(activity.isFinishing)
            complete.performClick()
            assertEquals(2, events.size)
            activity.receiveOwnerResult(AccountLoginActivity.RESULT_PENDING, Bundle.EMPTY)
            activity.receiveOwnerResult(AccountLoginActivity.RESULT_ACK, Bundle().apply { putBoolean(AccountLoginActivity.ACCEPTED, true) })
            assertTrue(activity.isFinishing)
        }
    }

    @Test fun doubaoStageFeedbackConsumesSanitizedIpcAndKeepsRealClickRetryUntilSuccessAck() {
        withDoubaoLogin { activity, browser, complete, status, events ->
            browser.loadUrl(DoubaoAccountProtocol.LOGIN)
            browser.webViewClient.onPageFinished(browser, browser.url)
            CookieManager.getInstance().setCookie(DoubaoAccountProtocol.LOGIN, "sessionid=fixture-session; Path=/chat; Secure")
            val failures = listOf(
                Triple("doubao_cookie_invalid", R.string.account_login_stage_cookie, R.string.account_login_stage_invalid),
                Triple("doubao_identity_invalid", R.string.account_login_stage_identity, R.string.account_login_stage_invalid),
                Triple("doubao_identity_http_401", R.string.account_login_stage_identity, R.string.account_login_http_401),
                Triple("doubao_identity_http_403", R.string.account_login_stage_identity, R.string.account_login_http_403),
                Triple("doubao_catalog_http_401", R.string.account_login_stage_catalog, R.string.account_login_http_401),
                Triple("doubao_catalog_http_403", R.string.account_login_stage_catalog, R.string.account_login_http_403),
                Triple("doubao_catalog_empty", R.string.account_login_stage_catalog, R.string.account_login_catalog_empty),
                Triple("doubao_catalog_empty_shape_1_0_0_0_0", R.string.account_login_stage_catalog, R.string.account_login_catalog_empty),
                Triple("doubao_catalog_network", R.string.account_login_stage_catalog, R.string.account_login_network_failed),
            )
            for ((index, failure) in failures.withIndex()) {
                assertTrue(complete.performClick())
                assertEquals(index + 1, events.size)
                activity.receiveOwnerResult(AccountLoginActivity.RESULT_PENDING, Bundle.EMPTY)
                val response = AccountLoginActivity.validationAck(failure.first)
                assertEquals(setOf(AccountLoginActivity.ACCEPTED, AccountLoginActivity.RETRY_ALLOWED, AccountLoginActivity.ERROR_CODE), response.keySet())
                val parcel = android.os.Parcel.obtain()
                try {
                    response.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    activity.receiveOwnerResult(AccountLoginActivity.RESULT_ACK, Bundle.CREATOR.createFromParcel(parcel))
                } finally { parcel.recycle() }
                assertEquals(activity.getString(R.string.account_login_stage_failure,
                    activity.getString(failure.second), activity.getString(failure.third), failure.first), status.text.toString())
                assertFalse(activity.isFinishing)
            }
            assertTrue(complete.performClick())
            assertEquals(failures.size + 1, events.size)
            activity.receiveOwnerResult(AccountLoginActivity.RESULT_ACK, AccountLoginActivity.validationAck(null))
            assertTrue(activity.isFinishing)
        }
    }

    @Test fun loginFailureIpcDropsUnknownOrCredentialBearingValuesAndUiRechecksWhitelist() {
        withDoubaoLogin { activity, _, _, status, _ ->
            for (untrusted in listOf("sessionid=private-marker", "doubao_identity_http_403\\nprivate-marker", "network_error")) {
                val response = AccountLoginActivity.validationAck(untrusted)
                assertFalse(response.getBoolean(AccountLoginActivity.ACCEPTED))
                assertTrue(response.getBoolean(AccountLoginActivity.RETRY_ALLOWED))
                assertFalse(response.containsKey(AccountLoginActivity.ERROR_CODE))
                response.putString(AccountLoginActivity.ERROR_CODE, untrusted)
                activity.receiveOwnerResult(AccountLoginActivity.RESULT_ACK, response)
                assertEquals(activity.getString(R.string.account_login_retry_validation), status.text.toString())
                assertFalse(status.text.toString().contains("private-marker"))
                assertFalse(activity.isFinishing)
            }
            activity.receiveOwnerResult(AccountLoginActivity.RESULT_ACK, AccountLoginActivity.validationAck("doubao_identity_http_503"))
            assertEquals(activity.getString(R.string.account_login_stage_failure,
                activity.getString(R.string.account_login_stage_identity),
                activity.getString(R.string.account_login_http_failed, "503"), "doubao_identity_http_503"), status.text.toString())
        }
    }

    // Deliberately bypass onCreate: this exercises production UI submission, not process/profile startup.
    private fun withDoubaoLogin(
        check: (AccountLoginActivity, WebView, Button, TextView, MutableList<Pair<Int, Bundle>>) -> Unit,
    ) {
        val activity = Robolectric.buildActivity(AccountLoginActivity::class.java).get()
        activity.intent = Intent().apply {
            putExtra(AccountLoginActivity.LOGIN_ID, "fixture-provider")
            putExtra(AccountLoginActivity.LOGIN_STATE, "fixture-state")
        }
        val events = mutableListOf<Pair<Int, Bundle>>()
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                events += resultCode to Bundle(requireNotNull(resultData))
            }
            // Robolectric's ResultReceiver shadow otherwise skips the framework delivery path.
            override fun send(resultCode: Int, resultData: Bundle?) {
                Shadow.directlyOn<Any?, ResultReceiver>(this, ResultReceiver::class.java, "send",
                    ClassParameter.from(Int::class.javaPrimitiveType, resultCode),
                    ClassParameter.from(Bundle::class.java, resultData))
            }
        }
        val windows = ReflectionHelpers.getStaticField<AccountLoginWindowCoordinator>(
            AccountLoginActivity::class.java, "windows")
        val token = windows.begin {}
        ReflectionHelpers.setField(activity, "windowToken", token)
        ReflectionHelpers.setField(activity, "receiver", receiver)
        ReflectionHelpers.setField(activity, "webCookieLogin", true)
        ReflectionHelpers.setField(activity, "webKind", AccountProviderKind.DOUBAO)
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies(null)
        var browser: WebView? = null
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showLogin",
                ClassParameter.from(String::class.java, DoubaoAccountProtocol.LOGIN))
            val actualBrowser = ReflectionHelpers.getField<WebView>(activity, "browser")
            browser = actualBrowser
            val status = ReflectionHelpers.getField<TextView>(activity, "status")
            val layout = actualBrowser.parent as LinearLayout
            val complete = (0 until layout.childCount).map { layout.getChildAt(it) }
                .filterIsInstance<Button>()
                .single { it.text.toString() == activity.getString(R.string.provider_account_dumate_complete) }
            check(activity, actualBrowser, complete, status, events)
        } finally {
            windows.close(token) {}
            browser?.destroy()
            cookies.removeAllCookies(null)
            ReflectionHelpers.setField(activity, "receiver", null)
            activity.finish()
        }
    }

    @Test fun deepSeekButtonReadsWebsiteStorageAndWaitsForOwnerAcknowledgement() {
        withDoubaoLogin { activity, browser, complete, status, events ->
            ReflectionHelpers.setField(activity, "webKind", AccountProviderKind.DEEPSEEK)
            browser.loadUrl("https://chat.deepseek.com/a/chat/fixture")
            browser.webViewClient.onPageFinished(browser, browser.url)
            assertTrue(complete.performClick())
            assertEquals(AccountLoginActivity.DEEPSEEK_TOKEN_SCRIPT, shadowOf(browser).lastEvaluatedJavascript)
            assertTrue(events.isEmpty())
            assertFalse(activity.isFinishing)
            shadowOf(browser).lastEvaluatedJavascriptCallback.onReceiveValue("\"synthetic-website-token-only\"")
            assertEquals(1, events.size)
            assertEquals(AccountLoginActivity.WEB_SESSION, events.single().first)
            val data = events.single().second
            assertEquals("deepseek", data.getString(AccountLoginActivity.LOGIN_KIND))
            assertEquals("https://chat.deepseek.com", data.getString(AccountLoginActivity.ORIGIN))
            assertEquals("fixture-provider", data.getString(AccountLoginActivity.LOGIN_ID))
            assertEquals("fixture-state", data.getString(AccountLoginActivity.LOGIN_STATE))
            assertEquals("synthetic-website-token-only", data.getString(AccountLoginActivity.WEB_COOKIE))
            assertEquals(activity.getString(R.string.account_login_validating), status.text.toString())
            assertFalse(activity.isFinishing)
            activity.receiveOwnerResult(AccountLoginActivity.RESULT_ACK, Bundle().apply { putBoolean(AccountLoginActivity.ACCEPTED, true) })
            assertTrue(activity.isFinishing)
        }
    }

    @Test fun deepSeekTokenReadRejectsMissingValueNavigationAndClosedWindow() {
        withDoubaoLogin { activity, browser, complete, status, events ->
            ReflectionHelpers.setField(activity, "webKind", AccountProviderKind.DEEPSEEK)
            browser.loadUrl("https://chat.deepseek.com/sign_in")
            browser.webViewClient.onPageFinished(browser, browser.url)
            complete.performClick()
            assertEquals(activity.getString(R.string.account_login_wrong_page), status.text.toString())
            assertTrue(events.isEmpty())
            browser.loadUrl("https://chat.deepseek.com/a/chat/fixture")
            browser.webViewClient.onPageFinished(browser, browser.url)
            complete.performClick()
            shadowOf(browser).lastEvaluatedJavascriptCallback.onReceiveValue("null")
            assertEquals(activity.getString(R.string.account_login_missing_web_token), status.text.toString())
            assertTrue(events.isEmpty())
            complete.performClick()
            val oldCallback = shadowOf(browser).lastEvaluatedJavascriptCallback
            browser.webViewClient.onPageStarted(browser, "https://example.invalid/", null)
            browser.loadUrl("https://example.invalid/")
            oldCallback.onReceiveValue("\"synthetic-website-token-only\"")
            assertTrue(events.isEmpty())
            browser.loadUrl("https://chat.deepseek.com/a/chat/fixture")
            browser.webViewClient.onPageFinished(browser, browser.url)
            complete.performClick()
            val lateCallback = shadowOf(browser).lastEvaluatedJavascriptCallback
            activity.finish()
            lateCallback.onReceiveValue("\"synthetic-website-token-only\"")
            assertTrue(events.isEmpty())
        }
    }

    @Test fun systemBarsCutoutAndKeyboardUseActualInsetsWithoutAccumulatingPadding() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val view = View(controller.get())
        controller.get().setContentView(view)
        AccountLoginWebPolicy.applyInsets(view)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 48))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(12, 36, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300)).build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
        ViewCompat.dispatchApplyWindowInsets(view, insets)
        assertEquals(12, view.paddingLeft)
        assertEquals(36, view.paddingTop)
        assertEquals(300, view.paddingBottom)
        controller.pause().stop().destroy()
    }
}
