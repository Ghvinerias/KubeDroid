package com.kubedroid.core.network.exec

import io.kubernetes.client.Exec
import io.kubernetes.client.openapi.ApiClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class ExecSessionImplTest {

    @Test
    fun test_outputFlow_emitsStdoutChunks() = runTest {
        val process = FakeExecProcess(
            stdout = ChunkedInputStream(listOf("out-1", "out-2")),
        )
        val session = ExecSessionImpl(
            process = process,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val emitted = async { session.outputFlow.take(2).toList() }

        advanceUntilIdle()

        assertEquals(listOf("out-1", "out-2"), emitted.await())
        session.close()
    }

    @Test
    fun test_errorFlow_emitsStderrChunks() = runTest {
        val process = FakeExecProcess(
            stderr = ChunkedInputStream(listOf("err-1", "err-2")),
        )
        val session = ExecSessionImpl(
            process = process,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val emitted = async { session.errorFlow.take(2).toList() }

        advanceUntilIdle()

        assertEquals(listOf("err-1", "err-2"), emitted.await())
        session.close()
    }

    @Test
    fun test_sendInput_writesUtf8AndFlushes() = runTest {
        val process = FakeExecProcess(waitForBlocks = true)
        val session = ExecSessionImpl(
            process = process,
            ioDispatcher = Dispatchers.IO,
        )

        session.sendInput("hello Привет")

        assertContentEquals(
            "hello Привет".toByteArray(StandardCharsets.UTF_8),
            process.stdinSink.writtenBytes(),
        )
        assertEquals(1, process.stdinSink.flushCount)
        session.close()
    }

    @Test
    fun test_resize_writesPayloadAndFlushes() = runTest {
        val process = FakeExecProcess(waitForBlocks = true)
        val session = ExecSessionImpl(
            process = process,
            ioDispatcher = Dispatchers.IO,
        )

        session.resize(columns = 120, rows = 40)

        assertEquals(
            "{\"Width\":120,\"Height\":40}",
            process.resizeSink.writtenString(),
        )
        assertEquals(1, process.resizeSink.flushCount)
        session.close()
    }

    @Test
    fun test_resize_invalidValues_doNotWrite() = runTest {
        val process = FakeExecProcess(waitForBlocks = true)
        val session = ExecSessionImpl(
            process = process,
            ioDispatcher = Dispatchers.IO,
        )

        session.resize(columns = 0, rows = 40)
        session.resize(columns = 120, rows = 0)
        session.resize(columns = -1, rows = -1)

        assertEquals("", process.resizeSink.writtenString())
        assertEquals(0, process.resizeSink.flushCount)
        session.close()
    }

    @Test
    fun test_connectionErrorMessage_emitsErrorAndUnknownExitCode() = runTest {
        val process = FakeExecProcess(
            connectionErrors = ByteArrayInputStream("connection dropped\n".toByteArray(StandardCharsets.UTF_8)),
            waitForBlocks = true,
            waitForResult = 55,
        )
        val session = ExecSessionImpl(
            process = process,
            ioDispatcher = Dispatchers.IO,
        )

        val errorDeferred = async { session.errorFlow.first() }
        val exitDeferred = async { session.exitCodeFlow.first() }

        advanceUntilIdle()

        assertEquals("connection dropped", withTimeout(2_000) { errorDeferred.await() })
        assertEquals(-1, withTimeout(2_000) { exitDeferred.await() })
        assertTrue(process.destroyCalled)
    }

    @Test
    fun test_waitFor_emitsActualExitCode_andClosesProcess() = runTest {
        val process = FakeExecProcess(waitForResult = 7)
        val session = ExecSessionImpl(
            process = process,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val exitDeferred = async { session.exitCodeFlow.first() }

        advanceUntilIdle()

        assertEquals(7, exitDeferred.await())
        assertTrue(process.destroyCalled)
    }

    private class FakeExecProcess(
        private val stdout: InputStream = ByteArrayInputStream(ByteArray(0)),
        private val stderr: InputStream = ByteArrayInputStream(ByteArray(0)),
        private val connectionErrors: InputStream = ByteArrayInputStream(ByteArray(0)),
        waitForBlocks: Boolean = false,
        private val waitForResult: Int = 0,
    ) : Exec.ExecProcess(ApiClient()) {

        val stdinSink = RecordingOutputStream()
        val resizeSink = RecordingOutputStream()
        var destroyCalled: Boolean = false
            private set

        private val waitForLatch = CountDownLatch(if (waitForBlocks) 1 else 0)
        @Volatile
        private var alive: Boolean = true

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun getConnectionErrorStream(): InputStream = connectionErrors

        override fun getOutputStream(): OutputStream = stdinSink

        override fun getResizeStream(): OutputStream = resizeSink

        override fun waitFor(): Int {
            waitForLatch.await()
            alive = false
            return waitForResult
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            val done = waitForLatch.await(timeout, unit)
            if (done) {
                alive = false
            }
            return done
        }

        override fun isAlive(): Boolean = alive

        override fun exitValue(): Int {
            check(!alive) { "Process still running" }
            return waitForResult
        }

        override fun destroy() {
            destroyCalled = true
            alive = false
            waitForLatch.countDown()
        }
    }

    private class RecordingOutputStream : OutputStream() {
        private val bytes = ArrayList<Byte>()
        var flushCount: Int = 0
            private set

        override fun write(b: Int) {
            bytes.add(b.toByte())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            repeat(len) { index ->
                bytes.add(b[off + index])
            }
        }

        override fun flush() {
            flushCount += 1
        }

        fun writtenBytes(): ByteArray = bytes.toByteArray()

        fun writtenString(): String = String(writtenBytes(), StandardCharsets.UTF_8)
    }

    private class ChunkedInputStream(
        chunks: List<String>,
    ) : InputStream() {

        private val encodedChunks = chunks.map { it.toByteArray(StandardCharsets.UTF_8) }
        private var chunkIndex: Int = 0
        private var offsetInChunk: Int = 0

        override fun read(): Int {
            val single = ByteArray(1)
            val count = read(single, 0, 1)
            return if (count < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (chunkIndex >= encodedChunks.size) return -1

            val chunk = encodedChunks[chunkIndex]
            val remaining = chunk.size - offsetInChunk
            val copySize = min(len, remaining)
            System.arraycopy(chunk, offsetInChunk, b, off, copySize)
            offsetInChunk += copySize

            if (offsetInChunk >= chunk.size) {
                chunkIndex += 1
                offsetInChunk = 0
            }

            return copySize
        }
    }
}
