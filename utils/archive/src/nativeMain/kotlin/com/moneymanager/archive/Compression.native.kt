package com.moneymanager.archive

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.Z_BEST_COMPRESSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_FINISH
import platform.zlib.Z_NEED_DICT
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflateEnd
import platform.zlib.deflateInit_
import platform.zlib.inflateEnd
import platform.zlib.inflateInit_
import platform.zlib.z_stream
import platform.zlib.zlibVersion
import platform.zlib.deflate as zDeflate
import platform.zlib.inflate as zInflate

private const val BUFFER_SIZE = 8 * 1024

// The default windowBits (zlib's deflateInit/inflateInit macros, which cinterop doesn't expose) gives the
// same zlib wrapper as java.util.zip's Deflater/Inflater.
@OptIn(ExperimentalForeignApi::class)
internal actual fun deflate(data: ByteArray): ByteArray =
    memScoped {
        val stream = zStream()
        check(deflateInit_(stream.ptr, Z_BEST_COMPRESSION, zlibVersion(), sizeOf<z_stream>().convert()) == Z_OK) {
            "deflateInit failed"
        }
        try {
            pump(stream, data) { zDeflate(stream.ptr, Z_FINISH) }
        } finally {
            deflateEnd(stream.ptr)
        }
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun inflate(data: ByteArray): ByteArray =
    memScoped {
        val stream = zStream()
        check(inflateInit_(stream.ptr, zlibVersion(), sizeOf<z_stream>().convert()) == Z_OK) { "inflateInit failed" }
        try {
            pump(stream, data) { zInflate(stream.ptr, Z_NO_FLUSH) }
        } finally {
            inflateEnd(stream.ptr)
        }
    }

// zlib reads zalloc/zfree/opaque during init, and null selects its default allocator.
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.zStream(): z_stream =
    alloc<z_stream>().apply {
        zalloc = null
        zfree = null
        opaque = null
    }

/**
 * Feeds all of [input] to [stream] and drains its output in [BUFFER_SIZE] chunks until [step] reports
 * the end of the stream. Mirrors the JVM inflater's guards: a preset dictionary means a malformed stream,
 * and running out of input before the end is only a normal stop for an empty payload.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun pump(
    stream: z_stream,
    input: ByteArray,
    step: () -> Int,
): ByteArray {
    // A zero-length array has no address to pin, so give zlib a one-byte buffer with avail_in = 0.
    val source = if (input.isEmpty()) ByteArray(1) else input
    val output = ByteArrayBuilder(maxOf(BUFFER_SIZE, input.size))
    val buffer = ByteArray(BUFFER_SIZE)
    source.usePinned { pinnedInput ->
        buffer.usePinned { pinnedBuffer ->
            stream.next_in = pinnedInput.addressOf(0).reinterpret()
            stream.avail_in = input.size.convert()
            var finished = false
            while (!finished) {
                stream.next_out = pinnedBuffer.addressOf(0).reinterpret()
                stream.avail_out = BUFFER_SIZE.convert()
                val result = step()
                output.append(buffer, BUFFER_SIZE - stream.avail_out.toInt())
                finished =
                    when (result) {
                        Z_STREAM_END -> true
                        Z_OK -> false
                        Z_NEED_DICT -> throw IllegalArgumentException("Compressed payload requires a preset dictionary")
                        // No progress was possible. Every round offers a fresh output buffer, so the input ran out
                        // before the end of the stream: fine for an empty payload, a truncated stream otherwise.
                        Z_BUF_ERROR -> {
                            check(stream.avail_in.toInt() == 0) { "zlib made no progress" }
                            require(input.isEmpty()) { "Compressed payload is truncated" }
                            true
                        }
                        else -> throw IllegalStateException("zlib failed with code $result")
                    }
            }
        }
    }
    return output.toByteArray()
}

private class ByteArrayBuilder(
    initialCapacity: Int,
) {
    private var bytes = ByteArray(initialCapacity)
    private var size = 0

    fun append(
        source: ByteArray,
        count: Int,
    ) {
        if (size + count > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + count))
        source.copyInto(bytes, size, 0, count)
        size += count
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}
