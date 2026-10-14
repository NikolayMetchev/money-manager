package com.moneymanager.terminal

import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val SHUTDOWN_GRACE_SECONDS = 10L

// The JDK only offers Ctrl-C as a shutdown hook. The hook cancels the tasks, then holds the JVM open (for a
// bounded time) until the command has printed its summary and closed the handle; the JVM then exits with 130.
internal actual fun installInterruptHandler(
    scope: CoroutineScope,
    onInterrupt: () -> Unit,
): AutoCloseable {
    val done = CountDownLatch(1)
    val hook =
        Thread({
            onInterrupt()
            done.await(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
        }, "mm-interrupt")
    Runtime.getRuntime().addShutdownHook(hook)
    return AutoCloseable {
        done.countDown()
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // Already shutting down: the hook is what's running.
        }
    }
}
