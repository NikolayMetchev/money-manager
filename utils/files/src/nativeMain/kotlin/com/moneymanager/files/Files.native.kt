package com.moneymanager.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

// USERPROFILE is Windows' equivalent of HOME.
@OptIn(ExperimentalForeignApi::class)
actual fun userHomeDirectory(): String? = (getenv("HOME") ?: getenv("USERPROFILE"))?.toKString()
