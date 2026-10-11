package com.moneymanager.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.errno
import platform.posix.stat
import platform.posix.strerror

// Windows' stat only has whole seconds.
@OptIn(ExperimentalForeignApi::class)
actual fun lastModifiedEpochMs(path: Path): Long =
    memScoped {
        val info = alloc<stat>()
        if (stat(path.toString(), info.ptr) != 0) throw IOException("Cannot stat $path: ${strerror(errno)?.toKString()}")
        info.st_mtime * MILLIS_PER_SECOND
    }

private const val MILLIS_PER_SECOND = 1_000L
