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
}
