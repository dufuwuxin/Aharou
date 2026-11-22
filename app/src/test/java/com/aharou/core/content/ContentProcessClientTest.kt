package com.aharou.core.content

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContentProcessClientTest {
    private val source = "<p>固定中文</p>".toByteArray()
    private val policy = ContentProcessPolicy.Snapshot(true, false)
    private val expected get() = ContentParser.parse(source, "UTF-8", "", false)

    @Test fun disabledNeverBinds() = runBlocking {
        val context = BindingContext(false)
        assertEquals(expected, ContentProcessClient(context).parseWithPolicy(source, "UTF-8", "", false,
            ContentProcessPolicy.Snapshot(false, false)))
        assertEquals(0, context.binds.get())
    }

    @Test fun rejectedBindingFallsBackExactlyOnceWithoutNetwork() = runBlocking {
        val context = BindingContext(false)
        assertEquals(expected, ContentProcessClient(context).parseWithPolicy(source, "UTF-8", "", false, policy))
        assertEquals(1, context.binds.get())
    }

    @Test fun timeoutUnbindsAndFallsBack() = runBlocking {
        val context = BindingContext(true)
        assertEquals(expected, ContentProcessClient(context).parseWithPolicy(source, "UTF-8", "", false, policy, 500))
        assertEquals(1, context.unbinds.get())
    }

    @Test fun cancellationClosesPipesCancelsRemoteAndUnbinds() = runBlocking {
        val remote = HoldingParser()
        val context = BindingContext(true, remote)
        val result = async(Dispatchers.Default) {
            ContentProcessClient(context).parseWithPolicy(source, "UTF-8", "", false, policy)
        }
        withTimeout(5000) { remote.started.await() }
        result.cancel()
        try { result.await(); throw AssertionError("Cancellation was swallowed") } catch (_: CancellationException) { }
        assertEquals(1, context.unbinds.get())
        assertTrue(remote.cancelled)
        remote.descriptors.forEach { assertFalse(it.fileDescriptor.valid()) }
    }

    @Test fun disconnectClosesPendingPipesAndFallsBack() = runBlocking {
        val remote = HoldingParser()
        val context = BindingContext(true, remote)
        val result = async(Dispatchers.Default) {
            ContentProcessClient(context).parseWithPolicy(source, "UTF-8", "", false, policy)
        }
        withTimeout(5000) { remote.started.await() }
        context.connection.onServiceDisconnected(ComponentName("com.aharou", "ContentParseService"))
        assertEquals(expected, withTimeout(5000) { result.await() })
        assertEquals(1, context.unbinds.get())
        remote.descriptors.forEach { assertFalse(it.fileDescriptor.valid()) }
    }

    private class BindingContext(private val accepted: Boolean, private val parser: HoldingParser? = null) :
        ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        val binds = AtomicInteger()
        val unbinds = AtomicInteger()
        lateinit var connection: ServiceConnection
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            binds.incrementAndGet()
            this.connection = connection
            parser?.let { connection.onServiceConnected(intent.component!!, it) }
            return accepted
        }
        override fun unbindService(connection: ServiceConnection) { unbinds.incrementAndGet() }
    }

    private class HoldingParser : IContentParser.Stub() {
        val started = CompletableDeferred<Unit>()
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        @Volatile var cancelled = false
        override fun processId(): Int = 1
        override fun processUid(): Int = 1
        override fun parse(id: String, input: ParcelFileDescriptor, output: ParcelFileDescriptor,
            charset: String?, baseUri: String, html: Boolean, xml: Boolean) {
            descriptors += ParcelFileDescriptor.dup(input.fileDescriptor)
            descriptors += ParcelFileDescriptor.dup(output.fileDescriptor)
            started.complete(Unit)
        }
        override fun cancel(id: String) {
            cancelled = true
            descriptors.forEach { runCatching { it.close() } }
        }
    }
}
