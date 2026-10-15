package com.moneymanager.terminal

import kotlinx.coroutines.CoroutineScope

/** The conventional exit status of a process stopped by Ctrl-C (128 + SIGINT). */
const val INTERRUPTED_EXIT_CODE = 130

/** Where Ctrl-C comes from: the OS in production, a test's own trigger in tests. */
fun interface Interrupts {
    /**
     * Calls [onInterrupt] (on [scope], or on a thread of the platform's choosing) the first time the person
     * presses Ctrl-C, until the returned handle is closed. A second Ctrl-C ends the process at once.
     */
    fun install(
        scope: CoroutineScope,
        onInterrupt: () -> Unit,
    ): AutoCloseable

    companion object {
        val System: Interrupts = Interrupts(::installInterruptHandler)
    }
}

/** Thrown once the person's Ctrl-C has cancelled the running tasks and their summary is on screen. */
class TasksInterruptedException : Exception("Interrupted")

internal expect fun installInterruptHandler(
    scope: CoroutineScope,
    onInterrupt: () -> Unit,
): AutoCloseable
