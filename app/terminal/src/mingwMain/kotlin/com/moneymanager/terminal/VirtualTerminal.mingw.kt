package com.moneymanager.terminal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.DWORDVar
import platform.windows.ENABLE_VIRTUAL_TERMINAL_PROCESSING
import platform.windows.GetConsoleMode
import platform.windows.GetStdHandle
import platform.windows.STD_ERROR_HANDLE
import platform.windows.SetConsoleMode

@OptIn(ExperimentalForeignApi::class)
actual fun enableStderrVirtualTerminal(): Boolean =
    memScoped {
        val handle = GetStdHandle(STD_ERROR_HANDLE)
        val mode = alloc<DWORDVar>()
        if (GetConsoleMode(handle, mode.ptr) == 0) return@memScoped false
        val wanted = mode.value or ENABLE_VIRTUAL_TERMINAL_PROCESSING.toUInt()
        wanted == mode.value || SetConsoleMode(handle, wanted) != 0
    }
