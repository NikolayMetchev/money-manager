package com.moneymanager.archive

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

private const val BUFFER_SIZE = 8 * 1024

internal actual fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    deflater.setInput(data)
    deflater.finish()
    val output = ByteArrayOutputStream(maxOf(BUFFER_SIZE, data.size / 2))
    val buffer = ByteArray(BUFFER_SIZE)
    try {
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
    } finally {
        deflater.end()
    }
    return output.toByteArray()
}

internal actual fun inflate(data: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(data)
    val output = ByteArrayOutputStream(maxOf(BUFFER_SIZE, data.size * 2))
    val buffer = ByteArray(BUFFER_SIZE)
    try {
        // An empty payload's stream ends without producing a byte, so test finished() after each call too.
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            output.write(buffer, 0, count)
            if (count == 0 && !inflater.finished()) {
                // We never deflate with a preset dictionary; needing one means a malformed stream — and
                // without this guard the loop would spin forever (count stays 0, finished stays false).
                require(!inflater.needsDictionary()) { "Compressed payload requires a preset dictionary" }
                check(inflater.needsInput()) { "Inflater made no progress" }
                // All input consumed before the end of the stream: fine for an empty payload, a truncated
                // stream otherwise. The native inflater applies the same rule.
                require(data.isEmpty()) { "Compressed payload is truncated" }
                break
            }
        }
    } finally {
        inflater.end()
    }
    return output.toByteArray()
}
