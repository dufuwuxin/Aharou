package com.aharou.core.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

internal class ProcessLineReader(input: InputStream, private val isAlive: () -> Boolean) : Closeable {
    private val lines = Channel<StreamLine>(1)
    private val closed = AtomicBoolean(false)
    private val worker = Thread({
        val polling = object : InputStream() {
            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (count == 0) return 0
                while (!closed.get()) {
                    val available = input.available()
                    if (available > 0) return input.read(bytes, offset, minOf(count, available))
                    if (!isAlive()) {
                        val finalBytes = input.available()
                        return if (finalBytes > 0) input.read(bytes, offset, minOf(count, finalBytes)) else -1
                    }
                    Thread.sleep(20)
                }
                return -1
            }
            override fun close() { input.close() }
        }
        val reader = BoundedLineReader(InputStreamReader(polling))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (lines.trySendBlocking(line).isFailure) break
            }
            lines.close()
        } catch (failure: Throwable) {
            lines.close(failure)
        } finally { runCatching { reader.close() } }
    }, "aharou-process-output").apply { isDaemon = true; start() }

    suspend fun readLine(): StreamLine? {
        val result = lines.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull()
    }

    fun fail(failure: Throwable) {
        closed.set(true)
        lines.close(failure)
        worker.interrupt()
    }

    override fun close() {
        closed.set(true)
        lines.cancel()
        worker.interrupt()
        worker.join(200)
    }
}
