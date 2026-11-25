package com.moneymanager.bazel.kmp.testing.launcher

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.getcwd
import platform.posix.pclose
import platform.posix.popen
import platform.posix.write
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
private val windows = Platform.osFamily == OsFamily.WINDOWS

private const val BUFFER_SIZE = 64 * 1024
private const val STDOUT = 1
private const val STDERR = 2

/**
 * Runs [command] with its stderr merged into its stdout, hands [onLine] each line, and returns its exit code.
 * Through `popen`, the one way to start a process that every Kotlin/Native target has: `/bin/sh` on Linux and
 * macOS, `cmd.exe` on Windows, so each gets its own quoting.
 */
@OptIn(ExperimentalForeignApi::class)
fun runMergingOutput(command: List<String>, onLine: (String) -> Unit): Int {
    val shellCommand =
        if (windows) {
            // cmd /c drops the first and the last quote of a command holding more than two, so wrap it in a pair.
            "\"" + command.joinToString(" ") { "\"" + it.replace("\"", "\\\"") + "\"" } + " 2>&1\""
        } else {
            command.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" } + " 2>&1"
        }
    val pipe = popen(shellCommand, "r") ?: error("Couldn't start ${command.first()}")
    var pending = ByteArray(0)
    val buffer = ByteArray(BUFFER_SIZE)
    while (true) {
        val read = buffer.usePinned { fread(it.addressOf(0), 1.convert(), BUFFER_SIZE.convert(), pipe).toInt() }
        if (read <= 0) break
        var lineStart = 0
        val chunk = pending + buffer.copyOf(read)
        for (i in chunk.indices) {
            if (chunk[i] == '\n'.code.toByte()) {
                onLine(line(chunk, lineStart, i))
                lineStart = i + 1
            }
        }
        pending = chunk.copyOfRange(lineStart, chunk.size)
    }
    if (pending.isNotEmpty()) onLine(line(pending, 0, pending.size))
    val status = pclose(pipe)
    return when {
        status == -1 -> 1
        windows -> status
        // A wait status: the exit code, or the signal that ended it as a shell reports one.
        (status and 0x7f) == 0 -> (status shr 8) and 0xff
        else -> 128 + (status and 0x7f)
    }
}

/** The bytes from [start] to [end] as a line, without the `\r` of a `\r\n` (as Java's `readLine`). */
private fun line(bytes: ByteArray, start: Int, end: Int): String {
    val stop = if (end > start && bytes[end - 1] == '\r'.code.toByte()) end - 1 else end
    return bytes.decodeToString(start, stop)
}

/** [path] against the working directory, as Java's `Path.toAbsolutePath` (no symlinks resolved). */
@OptIn(ExperimentalForeignApi::class)
fun absolutePath(path: String): String {
    val absolute =
        if (windows) {
            path.startsWith("/") || path.startsWith("\\") || (path.length > 1 && path[1] == ':')
        } else {
            path.startsWith("/")
        }
    val full =
        if (absolute) {
            path
        } else {
            val cwd = memScoped {
                val buf = allocArray<ByteVar>(BUFFER_SIZE)
                getcwd(buf, BUFFER_SIZE.convert())?.toKString() ?: error("Couldn't read the working directory")
            }
            "$cwd/$path"
        }
    return if (windows) full.replace('/', '\\') else full
}

fun printOut(line: String) = writeFd(STDOUT, line + "\n")

fun printErr(line: String) = writeFd(STDERR, line + "\n")

@OptIn(ExperimentalForeignApi::class)
private fun writeFd(fd: Int, text: String) {
    val bytes = text.encodeToByteArray()
    var offset = 0
    bytes.usePinned {
        while (offset < bytes.size) {
            val written = write(fd, it.addressOf(offset), (bytes.size - offset).convert()).toInt()
            if (written <= 0) return
            offset += written
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun writeFile(path: String, text: String) {
    val bytes = text.encodeToByteArray()
    val file = fopen(path, "wb") ?: error("Couldn't write $path")
    try {
        if (bytes.isNotEmpty()) {
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file) }
        }
    } finally {
        fclose(file)
    }
}
