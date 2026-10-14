package com.moneymanager.terminal

import platform.posix.STDERR_FILENO
import platform.posix.isatty

actual fun stderrIsTerminal(): Boolean = isatty(STDERR_FILENO) != 0
