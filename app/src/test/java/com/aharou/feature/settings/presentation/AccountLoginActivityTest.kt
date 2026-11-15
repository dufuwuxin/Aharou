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
