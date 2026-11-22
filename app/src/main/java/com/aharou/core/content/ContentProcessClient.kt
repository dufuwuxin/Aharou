package com.aharou.core.content

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentProcessClient @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun parse(bytes: ByteArray, charset: String?, baseUri: String, html: Boolean, xml: Boolean): String =
        parseWithPolicy(bytes, charset, baseUri, html, ContentProcessPolicy.capture(context), xml = xml)

    internal suspend fun parseWithPolicy(
        bytes: ByteArray, charset: String?, baseUri: String, html: Boolean,
        policy: ContentProcessPolicy.Snapshot, timeoutMillis: Long = 10_000, xml: Boolean = false
    ): String {
        require(bytes.size <= ContentParser.MAX_INPUT_BYTES)
        val remoteResult = if (policy.isolatedProcess) {
            try {
                withTimeoutOrNull(timeoutMillis) {
                    slots.withPermit { remoteParse(bytes, charset, baseUri, html, xml, policy.appZygote) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else null
        if (remoteResult != null) return remoteResult
        return localSlots.withPermit {
            withContext(Dispatchers.Default) {
                ensureActive()
                val result = ContentParser.parse(bytes, charset, baseUri, html, xml)
                ensureActive()
                result
            }
        }
    }

    private suspend fun remoteParse(
        bytes: ByteArray, charset: String?, baseUri: String, html: Boolean, xml: Boolean, zygote: Boolean
    ): String = withContext(Dispatchers.IO) {
        require(baseUri.length <= 16_384 && (charset?.length ?: 0) <= 128)
        coroutineScope {
            val session = Session(zygote)
            // Closing pipes on cancellation releases blocking read/write even on platforms where
            // interrupting a thread alone cannot wake a pipe syscall.
            val cancellationCloser = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { session.close() }
            }
            try {
                val remote = session.connect()
                val input = session.pipe()
                val output = session.pipe()
                remote.parse(session.id, input[0], output[1], charset, baseUri, html, xml)
                input[0].close()
                output[1].close()
                val writer = async(Dispatchers.IO) {
                    ParcelFileDescriptor.AutoCloseOutputStream(input[1]).use { it.write(bytes) }
                }
                val reader = async(Dispatchers.IO) {
                    DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(output[0])).use {
                        check(it.readInt() == ContentParseService.RESULT_MAGIC) { "Invalid content frame" }
                        val size = it.readInt()
                        require(size in 0..ContentParser.MAX_RESULT_BYTES)
                        val result = ContentParser.readBounded(it, ContentParser.MAX_RESULT_BYTES)
                        check(result.size == size) { "Incomplete content frame" }
                        ContentParser.decodeResult(result)
                    }
                }
                val result = select<String> {
                    reader.onAwait { it }
                    session.failure.onAwait { throw IOException("Content process disconnected") }
                }
                writer.await()
                result
            } finally {
                cancellationCloser.cancel()
                session.close()
            }
        }
    }

    private inner class Session(zygote: Boolean) : ServiceConnection, IBinder.DeathRecipient {
        val id: String = UUID.randomUUID().toString()
        val failure = CompletableDeferred<Unit>()
        private val connected = CompletableDeferred<IContentParser>()
        private val service = if (zygote && android.os.Build.VERSION.SDK_INT >= 29) {
            ZygoteContentParseService::class.java
        } else ContentParseService::class.java
        private val descriptors = mutableListOf<ParcelFileDescriptor>()
        private var remote: IContentParser? = null
        private var linkedBinder: IBinder? = null
        private var bound = false
        private var closed = false

        suspend fun connect(): IContentParser {
            synchronized(this) {
                check(!closed)
                bound = context.bindService(Intent(context, service), this, Context.BIND_AUTO_CREATE)
                if (!bound) throw IOException("Content service binding rejected")
            }
            val parser = connected.await()
            synchronized(this) {
                check(!closed)
                parser.asBinder().linkToDeath(this, 0)
                linkedBinder = parser.asBinder()
                remote = parser
            }
            return parser
        }

        @Synchronized fun pipe(): Array<ParcelFileDescriptor> {
            check(!closed)
            return ParcelFileDescriptor.createPipe().also { descriptors.addAll(it) }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(this) {
                if (closed) return
                connected.complete(IContentParser.Stub.asInterface(binder))
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
        override fun binderDied() = disconnected()

        private fun disconnected() {
            val error = IOException("Content process unavailable")
            connected.completeExceptionally(error)
            failure.completeExceptionally(error)
        }

        @Synchronized fun close() {
            if (closed) return
            closed = true
            descriptors.forEach { runCatching { it.close() } }
            descriptors.clear()
            remote?.let { runCatching { it.cancel(id) } }
            linkedBinder?.let { runCatching { it.unlinkToDeath(this, 0) } }
            if (bound) runCatching { context.unbindService(this) }
            bound = false
            remote = null
            linkedBinder = null
        }
    }

    companion object {
        private val slots = Semaphore(2)
        private val localSlots = Semaphore(2)
    }
}
