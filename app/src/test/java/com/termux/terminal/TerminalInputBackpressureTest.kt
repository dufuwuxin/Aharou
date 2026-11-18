package com.termux.terminal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class TerminalInputBackpressureTest {
    private val capacity = 1024 * 1024

    private class SlowBackend(private val fail: Boolean = false) : SessionBackend {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        override fun getInputStream() = object : InputStream() {
            override fun read(): Int { closed.await(); return -1 }
        }
        override fun getOutputStream() = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                if (fail) throw IOException("fixture write failure")
                synchronized(output) { output.write(bytes, offset, count) }
            }
        }
        override fun resize(columns: Int, rows: Int) {}
        override fun waitForExit(): Int { closed.await(); return 0 }
        override fun close() { closed.countDown(); release.countDown() }
    }

    private fun client(rejected: AtomicInteger): TerminalSessionClient = Proxy.newProxyInstance(
        TerminalSessionClient::class.java.classLoader, arrayOf(TerminalSessionClient::class.java)
    ) { _, method, _ ->
        if (method.name == "onInputRejected") rejected.incrementAndGet()
        if (method.name == "getTerminalCursorStyle") 0 else null
    } as TerminalSessionClient

    // The old implementation blocks here; always close the queue and join the worker on failure.
    private fun writePromptly(session: TerminalSession, bytes: ByteArray) {
        val task = FutureTask { session.write(bytes, 0, bytes.size) }
        val worker = Thread(task, "input-regression-main-write")
        worker.start()
        try { task.get(500, TimeUnit.MILLISECONDS) }
        finally {
            if (!task.isDone) session.cleanupResources(0)
            worker.join(1000)
            assertFalse("write worker leaked", worker.isAlive)
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(condition())
    }

    @Test fun stalledWriterDoesNotBlockAndOverflowWritesNoPrefix() {
        val rejected = AtomicInteger()
        val backend = SlowBackend()
        val session = TerminalSession(100, client(rejected), backend)
        session.updateSize(80, 24)
        try {
            val first = byteArrayOf(1, 2, 3)
            writePromptly(session, first)
            assertTrue(backend.entered.await(1, TimeUnit.SECONDS))
            val accepted = ByteArray(capacity) { (it % 127).toByte() }
            writePromptly(session, accepted)
            writePromptly(session, byteArrayOf(99, 98))
            assertEquals(1, rejected.get())
            backend.release.countDown()
            awaitCondition { synchronized(backend.output) { backend.output.size() == first.size + accepted.size } }
            assertArrayEquals(first + accepted, synchronized(backend.output) { backend.output.toByteArray() })
        } finally { session.cleanupResources(0) }
    }

    @Test fun mainLooperWriteReturnsWhileBackendIsStalled() {
        assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
        val backend = SlowBackend()
        val rejected = AtomicInteger()
        val session = TerminalSession(100, client(rejected), backend)
        session.updateSize(80, 24)
        val returned = CountDownLatch(1)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread {
            if (!returned.await(500, TimeUnit.MILLISECONDS)) {
                timedOut.set(true)
                session.cleanupResources(0)
            }
        }
        try {
            session.write(byteArrayOf(1), 0, 1)
            assertTrue(backend.entered.await(1, TimeUnit.SECONDS))
            watchdog.start()
            session.write(ByteArray(capacity), 0, capacity)
            session.write(byteArrayOf(2), 0, 1)
            returned.countDown()
            watchdog.join(1000)
            assertFalse("main write waited for backend drain", timedOut.get())
            assertEquals(1, rejected.get())
        } finally {
            returned.countDown()
            session.cleanupResources(0)
            if (watchdog.isAlive) watchdog.join(1000)
        }
    }

    @Test fun oversizedCallIsRejectedWholeAndNextInputIsUnchanged() {
        val rejected = AtomicInteger()
        val session = TerminalSession(100, client(rejected), SlowBackend())
        try {
            writePromptly(session, ByteArray(capacity + 1) { 7 })
            assertEquals(1, rejected.get())
            assertEquals(0, session.mTerminalToProcessIOQueue.read(ByteArray(8), false))
            writePromptly(session, byteArrayOf(4, 5))
            val result = ByteArray(2)
            assertEquals(2, session.mTerminalToProcessIOQueue.read(result, false))
            assertArrayEquals(byteArrayOf(4, 5), result)
        } finally { session.cleanupResources(0) }
    }

    @Test fun writerExceptionClosesInputAndRejectsLaterCalls() {
        val rejected = AtomicInteger()
        val backend = SlowBackend(fail = true)
        val session = TerminalSession(100, client(rejected), backend)
        session.updateSize(80, 24)
        try {
            writePromptly(session, byteArrayOf(1))
            assertTrue(backend.entered.await(1, TimeUnit.SECONDS))
            backend.release.countDown()
            awaitCondition { session.mTerminalToProcessIOQueue.read(ByteArray(0), false) == -1 }
            writePromptly(session, byteArrayOf(2))
            assertEquals(1, rejected.get())
        } finally { session.cleanupResources(0) }
    }

    @Test fun bracketedPasteIsOneAtomicPacket() {
        val rejected = AtomicInteger()
        val backend = SlowBackend()
        val session = TerminalSession(100, client(rejected), backend)
        session.updateSize(80, 24)
        try {
            val enable = "\u001b[?2004h".toByteArray()
            session.emulator.append(enable, enable.size)
            val text = "中文\nline"
            session.emulator.paste(text)
            assertTrue(backend.entered.await(1, TimeUnit.SECONDS))
            backend.release.countDown()
            val expected = "\u001b[200~中文\rline\u001b[201~".toByteArray()
            awaitCondition { synchronized(backend.output) { backend.output.size() == expected.size } }
            assertArrayEquals(expected, synchronized(backend.output) { backend.output.toByteArray() })
            val fill = ByteArray(capacity - 6) { 8 }
            // Freeze the pump by testing the production queue before starting another session.
            val pending = TerminalSession(100, client(rejected), SlowBackend())
            try {
                pending.write(fill, 0, fill.size)
                val emulator = TerminalEmulator(pending, 80, 24, 100, client(rejected))
                emulator.append(enable, enable.size)
                val task = FutureTask { emulator.paste("payload") }
                val worker = Thread(task)
                worker.start()
                try {
                    task.get(500, TimeUnit.MILLISECONDS)
                    assertEquals(1, rejected.get())
                    val remaining = ByteArray(capacity)
                    assertEquals(fill.size, pending.mTerminalToProcessIOQueue.read(remaining, false))
                    assertArrayEquals(fill, remaining.copyOf(fill.size))
                } finally {
                    pending.cleanupResources(0)
                    worker.join(1000)
                    assertFalse("paste worker leaked", worker.isAlive)
                }
            } finally { pending.cleanupResources(0) }
        } finally { session.cleanupResources(0) }
    }

    @Test fun tryWriteWrapsInOrderAndClosedQueueRejects() {
        val queue = ByteQueue(5)
        assertTrue(queue.tryWrite(byteArrayOf(1, 2, 3, 4), 0, 4))
        assertEquals(3, queue.read(ByteArray(3), false))
        assertTrue(queue.tryWrite(byteArrayOf(5, 6, 7, 8), 0, 4))
        assertFalse(queue.tryWrite(byteArrayOf(9), 0, 1))
        val result = ByteArray(5)
        assertEquals(5, queue.read(result, false))
        assertArrayEquals(byteArrayOf(4, 5, 6, 7, 8), result)
        queue.close()
        assertFalse(queue.tryWrite(byteArrayOf(9), 0, 1))
    }
}
