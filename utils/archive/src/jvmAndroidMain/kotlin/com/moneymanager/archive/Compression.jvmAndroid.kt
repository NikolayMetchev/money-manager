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
            if (count == 0 && inflater.needsInput()) break
            output.write(buffer, 0, count)
        }
    } finally {
        inflater.end()
    }
    return output.toByteArray()
}
