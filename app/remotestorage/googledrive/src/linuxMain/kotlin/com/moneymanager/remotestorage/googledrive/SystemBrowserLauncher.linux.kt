package com.moneymanager.remotestorage.googledrive

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.FD_CLOEXEC
import platform.posix.F_SETFD
import platform.posix._exit
import platform.posix.close
import platform.posix.execvp
import platform.posix.fcntl
import platform.posix.fork
import platform.posix.pipe
import platform.posix.read
import platform.posix.waitpid
import platform.posix.write

/**
 * Starts `xdg-open` without waiting for it: depending on the desktop, it can run for as long as the browser
 * it launched, and the sign-in must go on to read the loopback callback meanwhile. A double fork hands the
 * opener to init (so it never lingers as a zombie), and a close-on-exec pipe still reports a failed exec:
 * EOF means `xdg-open` started, a byte means it couldn't be run.
 *
 * It's exec'd with an argv rather than through a shell, because the consent URL is full of `&`, which a
 * shell would treat as command separators.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun openInSystemBrowser(url: String): Boolean =
    memScoped {
        // Everything the children touch is allocated before forking: they may only exec, write and exit.
        val argv = allocArrayOf("xdg-open".cstr.ptr, url.cstr.ptr, null)
        val failure = alloc<ByteVar>()
        val fds = allocArray<IntVar>(2)
        if (pipe(fds) != 0) return@memScoped false
        val readEnd = fds[0]
        val writeEnd = fds[1]
        fcntl(writeEnd, F_SETFD, FD_CLOEXEC)
        when (val child = fork()) {
            -1 -> {
                close(readEnd)
                close(writeEnd)
                false
            }
            0 -> {
                close(readEnd)
                when (fork()) {
                    0 -> {
                        execvp("xdg-open", argv)
                        write(writeEnd, failure.ptr, 1u)
                    }
                    -1 -> write(writeEnd, failure.ptr, 1u)
                }
                _exit(0)
                false
            }
            else -> {
                close(writeEnd)
                waitpid(child, null, 0)
                val launched = read(readEnd, failure.ptr, 1u) == 0L
                close(readEnd)
                launched
            }
        }
    }
