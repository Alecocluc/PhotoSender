package com.appharbor.pherry.data.upload

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/** Streams one protocol chunk using a per-file buffer; media size never determines heap use. */
internal class UploadChunkBody(
    private val input: InputStream,
    private val length: Long,
    private val buffer: ByteArray,
    private val isCancelled: () -> Boolean,
    private val onBytes: (Long) -> Unit,
) : RequestBody() {
    init {
        require(length >= 0)
        require(buffer.isNotEmpty())
    }

    override fun contentType() = OCTET_STREAM
    override fun contentLength() = length
    // Retrying this body would consume the next bytes of the shared source stream. Resume must
    // instead reconcile the receiver's durable offset and reopen the source through UploadManager.
    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        var remaining = length
        while (remaining > 0) {
            if (isCancelled()) throw InterruptedIOException("Transfer paused")
            val count = try {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == 0) {
                    val byte = input.read()
                    if (byte < 0) -1 else { buffer[0] = byte.toByte(); 1 }
                } else read
            } catch (e: IOException) { throw SourceReadException(e) }
            catch (e: SecurityException) { throw SourceReadException(e) }
            if (count < 0) throw SourceReadException(EOFException("The original ended before its recorded size"))
            if (isCancelled()) throw InterruptedIOException("Transfer paused")
            sink.write(buffer, 0, count)
            onBytes(count.toLong())
            remaining -= count
        }
    }

    private companion object { val OCTET_STREAM = "application/octet-stream".toMediaType() }
}
