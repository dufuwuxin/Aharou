package com.aharou.core.content

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.DataOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Deliberately not a Hilt service: the isolated UID owns no application resources. */
open class ContentParseService : Service() {
    private val requests = mutableMapOf<String, FutureTask<Unit>>()
    private var destroyed = false
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { work -> Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            work.run()
        }, "content-parser") },
        ThreadPoolExecutor.AbortPolicy()
    )

    private val binder = object : IContentParser.Stub() {
        override fun processId(): Int = Process.myPid()
        override fun processUid(): Int = Process.myUid()

        override fun parse(
            requestId: String, input: ParcelFileDescriptor, output: ParcelFileDescriptor,
            charset: String?, baseUri: String, html: Boolean, xml: Boolean
        ) {
            if (requestId.length > 64 || baseUri.length > 16_384 || (charset?.length ?: 0) > 128) {
                close(input, output)
                return
            }
            val task = object : FutureTask<Unit>(Callable {
                try {
                    val bytes = ParcelFileDescriptor.AutoCloseInputStream(input).use {
                        ContentParser.readBounded(it, ContentParser.MAX_INPUT_BYTES)
                    }
                    if (!Thread.currentThread().isInterrupted) {
                        val result = ContentParser.encodeResult(ContentParser.parse(bytes, charset, baseUri, html, xml))
                        DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(output)).use {
                            it.writeInt(RESULT_MAGIC)
                            it.writeInt(result.size)
                            it.write(result)
                        }
                    }
                } catch (_: Exception) {
                    // EOF without a complete frame tells the client to use its local parser.
                }
                Unit
            }) {
                override fun done() {
                    close(input, output)
                    synchronized(requests) {
                        if (requests[requestId] === this) requests.remove(requestId)
                    }
                }
            }
            synchronized(requests) {
                if (destroyed || requests.size >= 2 || requests.containsKey(requestId)) {
                    task.cancel(false)
                    return
                }
                requests[requestId] = task
                try {
                    executor.execute(task)
                } catch (_: RejectedExecutionException) {
                    task.cancel(false)
                }
            }
        }

        override fun cancel(requestId: String) {
            synchronized(requests) { requests[requestId] }?.let {
                it.cancel(true)
                executor.remove(it)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        val pending = synchronized(requests) {
            destroyed = true
            requests.values.toList()
        }
        pending.forEach { it.cancel(true) }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun close(vararg descriptors: ParcelFileDescriptor) {
        descriptors.forEach { runCatching { it.close() } }
    }

    companion object {
        internal const val RESULT_MAGIC = 0x43505231
    }
}

class ZygoteContentParseService : ContentParseService()
