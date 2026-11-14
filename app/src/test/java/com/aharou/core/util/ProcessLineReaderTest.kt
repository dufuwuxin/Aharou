package com.aharou.core.util

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProcessLineReaderTest {
    private class Blocked : InputStream() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun available(): Int { entered.countDown(); return 0 }
        override fun read(): Int {
            while (released.count > 0) {
                try { released.await() } catch (_: InterruptedException) { }
            }
            return -1
        }
        override fun close() { released.countDown(); closed.countDown() }
    }

    @Test(timeout = 5000) fun deadlineUnblocksTheConsumerEvenWhenThePipeHasNotReachedEof() = runBlocking {
        supervisorScope {
            val input = Blocked()
            val reader = ProcessLineReader(input) { true }
            try {
                val waiting = async { reader.readLine() }
                yield()
                assertTrue(input.entered.await(1, TimeUnit.SECONDS))
                reader.fail(IOException("fixture deadline"))
                var failed = false
                try { waiting.await() } catch (_: IOException) { failed = true }
                assertTrue(failed)
            } finally {
                reader.close()
                assertTrue("The pipe worker must close its stream", input.closed.await(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test(timeout = 5000) fun coroutineCancellationDoesNotWaitForTheBlockingInputRead() = runBlocking {
        val input = Blocked()
        val reader = ProcessLineReader(input) { true }
        try {
            val waiting = launch { reader.readLine() }
            yield()
            assertTrue(input.entered.await(1, TimeUnit.SECONDS))
            waiting.cancelAndJoin()
            assertTrue(waiting.isCancelled)
        } finally {
            reader.close()
            assertTrue("The cancelled reader must be closed", input.closed.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun finalOutputArrivingBetweenEmptyCheckAndRootExitIsStillDrained() = runBlocking {
        var bytes = byteArrayOf()
        var offset = 0
        val input = object : InputStream() {
            override fun available() = bytes.size - offset
            override fun read(): Int = if (offset == bytes.size) -1 else bytes[offset++].toInt() and 255
        }
        var checked = false
        val reader = ProcessLineReader(input) {
            if (!checked) { bytes = "tail\n".toByteArray(); checked = true }
            false
        }
        try {
            assertEquals("tail", reader.readLine()?.text)
            assertNull(reader.readLine())
        } finally { reader.close() }
    }

    @Test fun completeLinesAndFinalTailArePreserved() = runBlocking {
        val reader = ProcessLineReader("one\ntwo".byteInputStream()) { false }
        try {
            assertEquals("one", reader.readLine()?.text)
            assertEquals("two", reader.readLine()?.text)
            assertNull(reader.readLine())
        } finally { reader.close() }
    }
}
