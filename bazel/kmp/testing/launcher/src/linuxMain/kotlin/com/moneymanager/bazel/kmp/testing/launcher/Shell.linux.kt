package com.moneymanager.bazel.kmp.testing.launcher

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.FILE
import platform.posix.pclose
import platform.posix.popen

// The same on Linux and macOS: /bin/sh's quoting and a wait status.

@OptIn(ExperimentalForeignApi::class)
actual fun openShell(command: List<String>): CPointer<FILE>? =
    popen(command.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" } + " 2>&1", "r")

@OptIn(ExperimentalForeignApi::class)
actual fun closeShell(pipe: CPointer<FILE>): Int {
    val status = pclose(pipe)
    return when {
        status == -1 -> 1

        // The exit code, or the signal that ended it as a shell reports one.
        (status and 0x7f) == 0 -> (status shr 8) and 0xff

        else -> 128 + (status and 0x7f)
    }
}
