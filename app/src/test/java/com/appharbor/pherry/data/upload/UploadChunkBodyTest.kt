package com.appharbor.pherry.data.upload

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

class UploadChunkBodyTest {
    @Test fun sequentialChunksPreserveEveryByteWithoutReadingIntoTheNextChunk() {
        val original = ByteArray(4 * 1024 * 1024 + 37) { (it % 251).toByte() }
        val input = ByteArrayInputStream(original)
        val buffer = ByteArray(128 * 1024)
        val output = Buffer()
        var sent = 0L
        for (size in listOf(4 * 1024 * 1024, 37)) {
            val body = UploadChunkBody(input, size.toLong(), buffer, { false }) { sent += it }
            assertTrue("Shared source streams cannot be replayed automatically", body.isOneShot())
            assertEquals(size.toLong(), body.contentLength())
            body.writeTo(output)
        }
        assertEquals(original.size.toLong(), sent)
        assertEquals(0, input.available())
        assertArrayEquals(original, output.readByteArray())
    }

    @Test fun cancellationStopsBeforeReadingAnotherBuffer() {
        val input = ByteArrayInputStream(ByteArray(512 * 1024))
        val cancelled = AtomicBoolean()
        val body = UploadChunkBody(input, 512L * 1024, ByteArray(128 * 1024), cancelled::get) { cancelled.set(true) }
        assertThrows(InterruptedIOException::class.java) { body.writeTo(Buffer()) }
        assertEquals(384 * 1024, input.available())
    }

    @Test fun anEarlyEndIsASourceFailureRatherThanARetryableConnectionError() {
        val body = UploadChunkBody(ByteArrayInputStream(ByteArray(5)), 10, ByteArray(128), { false }) { }
        val failure = assertThrows(SourceReadException::class.java) { body.writeTo(Buffer()) }
        assertFalse(failure.shouldRetryTransfer())
        assertEquals(TransferReason.SOURCE_UNAVAILABLE, failure.transferReason())
    }

    @Test fun revokedAccessIsReportedThroughOkHttpAsASourceIOException() {
        val input = object : InputStream() { override fun read(): Int = throw SecurityException("Access revoked") }
        val body = UploadChunkBody(input, 1, ByteArray(128), { false }) { }
        val failure = assertThrows(SourceReadException::class.java) { body.writeTo(Buffer()) }
        assertTrue(failure.cause is SecurityException)
        assertFalse(failure.shouldRetryTransfer())
    }
}
