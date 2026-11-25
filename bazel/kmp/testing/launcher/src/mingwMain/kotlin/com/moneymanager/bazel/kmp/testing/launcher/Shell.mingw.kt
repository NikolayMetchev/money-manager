package com.moneymanager.bazel.kmp.testing.launcher

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.FILE
import platform.posix._pclose
import platform.posix._popen

// cmd.exe drops the first and the last quote of a command holding more than two, so the command is wrapped in a pair.
@OptIn(ExperimentalForeignApi::class)
actual fun openShell(command: List<String>): CPointer<FILE>? =
    _popen("\"" + command.joinToString(" ") { "\"" + it.replace("\"", "\\\"") + "\"" } + " 2>&1\"", "r")

@OptIn(ExperimentalForeignApi::class)
actual fun closeShell(pipe: CPointer<FILE>): Int = _pclose(pipe).let { if (it == -1) 1 else it }
