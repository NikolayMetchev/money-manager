package com.moneymanager.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

// On Windows, USERPROFILE is the home; HOME, when set at all, often comes from MSYS/Git Bash in Unix
// form (`/c/Users/...`), so it is only a fallback there.
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual fun userHomeDirectory(): String? {
    val order = if (Platform.osFamily == OsFamily.WINDOWS) listOf("USERPROFILE", "HOME") else listOf("HOME", "USERPROFILE")
    return order.firstNotNullOfOrNull { name -> getenv(name)?.toKString()?.ifBlank { null } }
}
