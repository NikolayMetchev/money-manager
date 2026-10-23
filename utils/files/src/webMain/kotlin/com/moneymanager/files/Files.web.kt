package com.moneymanager.files

import kotlinx.io.files.Path

/** A browser has no home directory. */
actual fun userHomeDirectory(): String? = null

actual fun lastModifiedEpochMs(path: Path): Long = throw UnsupportedOperationException("No file times in the browser: $path")
