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
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            when {
                count > 0 -> output.write(buffer, 0, count)
                // We never deflate with a preset dictionary; needing one means a malformed stream — and
                // without this guard the loop would spin forever (count stays 0, finished stays false).
                inflater.needsDictionary() -> throw IllegalArgumentException("Compressed payload requires a preset dictionary")
                // All input consumed: normal end (incl. an empty payload). Upstream AES-GCM already
                // authenticates these compressed bytes, so a truncated archive fails before we get here.
                inflater.needsInput() -> break
                else -> throw IllegalStateException("Inflater made no progress")
            }
        }
    } finally {
        inflater.end()
    }
    return output.toByteArray()
}
