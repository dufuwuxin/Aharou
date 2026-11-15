package com.aharou.feature.settings.presentation

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import com.aharou.feature.settings.presentation.component.forAccountLoginIpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class AccountLoginReceiverTest {
    @Suppress("DEPRECATION")
    private fun transfer(receiver: ResultReceiver): ResultReceiver {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(Bundle().apply { putParcelable("receiver", receiver.forAccountLoginIpc()) })
            parcel.setDataPosition(0)
            val bundle = requireNotNull(parcel.readBundle(null))
            requireNotNull(bundle.getParcelable("receiver"))
        } finally { parcel.recycle() }
    }

    private fun send(receiver: ResultReceiver, code: Int, data: Bundle) {
        org.robolectric.shadow.api.Shadow.directlyOn<Any?, ResultReceiver>(receiver, ResultReceiver::class.java, "send",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, code),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Bundle::class.java, data))
    }

    @Test fun readyCloseReceiverUsesFrameworkClassAndKeepsBinderCallback() {
        var result: Int? = null
        val local = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { result = resultCode }
        }
        val remote = transfer(local)
        assertEquals(ResultReceiver::class.java, remote.javaClass)
        send(remote, 0, Bundle.EMPTY)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, result)
    }

    @Test fun loginCallbackAndCancellationSurviveBootClassLoaderWithoutCredentials() {
        val events = mutableListOf<Pair<Int, String?>>()
        val local = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                events += resultCode to resultData?.getString("url")
            }
        }
        val remote = transfer(local)
        send(remote, 2, Bundle().apply { putString("url", "app://callback?code=test") })
        send(remote, 3, Bundle.EMPTY)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(2 to "app://callback?code=test", 3 to null), events)
        assertFalse(remote === local)
    }
    @Test fun explicitWebsiteCookieUsesPrivateBundleAndKeepsOriginLoginIdentityAndState() {
        val events = mutableListOf<Bundle>()
        val local = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                assertEquals(com.aharou.feature.settings.presentation.component.AccountLoginActivity.WEB_SESSION, resultCode)
                events += requireNotNull(resultData)
            }
        }
        val remote = transfer(local)
        val activity = com.aharou.feature.settings.presentation.component.AccountLoginActivity
        send(remote, activity.WEB_SESSION, Bundle().apply {
            putString(activity.ORIGIN, "https://www.dumate.cn")
            putString(activity.LOGIN_ID, "fixture-provider")
            putString(activity.LOGIN_STATE, "fixture-state")
            putString(activity.WEB_COOKIE, "normalcookie=fixture-session")
        })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, events.size)
        assertEquals("https://www.dumate.cn", events.single().getString(activity.ORIGIN))
        assertEquals("fixture-provider", events.single().getString(activity.LOGIN_ID))
        assertEquals("fixture-state", events.single().getString(activity.LOGIN_STATE))
        assertEquals("normalcookie=fixture-session", events.single().getString(activity.WEB_COOKIE))
        assertFalse(events.single().containsKey(activity.URL))
        assertEquals(ResultReceiver::class.java, remote.javaClass)
    }

    @Test fun rejectedAcknowledgementContainsNoCredentialsAndKeepsWindowRetryable() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val local = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                events += resultCode to (resultData?.getBoolean("account_login_accepted") == true)
                assertFalse(resultData?.containsKey("account_web_cookie") == true)
            }
        }
        val remote = transfer(local)
        send(remote, 5, Bundle().apply { putBoolean("account_login_accepted", false) })
        send(remote, 5, Bundle().apply { putBoolean("account_login_accepted", true) })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(5 to false, 5 to true), events)
    }

}
