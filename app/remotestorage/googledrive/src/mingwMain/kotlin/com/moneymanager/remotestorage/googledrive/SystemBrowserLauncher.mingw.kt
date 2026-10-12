package com.moneymanager.remotestorage.googledrive

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toLong
import platform.windows.SW_SHOWNORMAL
import platform.windows.ShellExecuteW

// ShellExecute reports success as a value greater than 32; anything lower is an error code.
private const val SHELL_EXECUTE_MAX_ERROR = 32L

@OptIn(ExperimentalForeignApi::class)
internal actual fun openInSystemBrowser(url: String): Boolean =
    ShellExecuteW(null, "open", url, null, null, SW_SHOWNORMAL).toLong() > SHELL_EXECUTE_MAX_ERROR
