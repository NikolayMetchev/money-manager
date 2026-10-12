package com.moneymanager.remotestorage.googledrive

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix._exit
import platform.posix.execvp
import platform.posix.fork
import platform.posix.waitpid

private const val EXEC_FAILED = 127

// Exec'd with an argv rather than through a shell: the consent URL is full of `&`, which a shell would
// treat as command separators. xdg-open exits once the browser has been handed the URL.
@OptIn(ExperimentalForeignApi::class)
internal actual fun openInSystemBrowser(url: String): Boolean =
    memScoped {
        // Built before forking: the child must only exec, never allocate.
        val argv = allocArrayOf("xdg-open".cstr.ptr, url.cstr.ptr, null)
        when (val pid = fork()) {
            -1 -> false
            0 -> {
                execvp("xdg-open", argv)
                _exit(EXEC_FAILED)
                false
            }
            else -> {
                val status = alloc<IntVar>()
                // WIFEXITED(status) && WEXITSTATUS(status) == 0; the macros aren't visible to cinterop.
                waitpid(pid, status.ptr, 0) != -1 && status.value and 0x7f == 0 && (status.value shr 8) and 0xff == 0
            }
        }
    }
