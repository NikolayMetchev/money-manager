@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.archive

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.khronos.webgl.toInt8Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

// "deflate" is the zlib-wrapped (RFC 1950) format, as java.util.zip and the platform zlib write.
private const val ZLIB = "deflate"

internal actual suspend fun deflate(data: ByteArray): ByteArray = transform(data, compress = true)

@Suppress("TooGenericExceptionCaught") // The stream rejects a bad payload with a JavaScript TypeError, no Exception.
internal actual suspend fun inflate(data: ByteArray): ByteArray {
    // As on the other platforms, an empty payload is the one input that may end before a stream does.
    if (data.isEmpty()) return data
    return try {
        transform(data, compress = false)
    } catch (failure: Throwable) {
        throw IllegalArgumentException("Compressed payload is truncated or malformed: ${failure.message}")
    }
}

private suspend fun transform(
    data: ByteArray,
    compress: Boolean,
): ByteArray = Int8Array(pipe(data.toInt8Array(), ZLIB, compress).await<ArrayBuffer>()).toByteArray()

// The parameters are read by the JavaScript.
@Suppress("UnusedParameter")
private fun pipe(
    bytes: Int8Array,
    format: String,
    compress: Boolean,
): Promise<ArrayBuffer> =
    js(
        """new Response(new Blob([bytes]).stream().pipeThrough(
            compress ? new CompressionStream(format) : new DecompressionStream(format))).arrayBuffer()""",
    )
