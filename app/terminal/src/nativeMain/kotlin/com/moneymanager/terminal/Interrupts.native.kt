package com.moneymanager.terminal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.posix.SIGINT
import platform.posix.SIG_DFL
import platform.posix._exit
import platform.posix.signal
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalAtomicApi::class)
private val interrupts = AtomicInt(0)

private val POLL_INTERVAL = 100.milliseconds

// A signal handler may only do async-signal-safe work, so it just counts; a coroutine notices the count.
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
internal actual fun installInterruptHandler(
    scope: CoroutineScope,
    onInterrupt: () -> Unit,
): AutoCloseable {
    interrupts.store(0)
    signal(
        SIGINT,
        staticCFunction { _: Int ->
            if (interrupts.fetchAndAdd(1) > 0) _exit(INTERRUPTED_EXIT_CODE)
        },
    )
    val watcher =
        scope.launch {
            while (interrupts.load() == 0) delay(POLL_INTERVAL)
            onInterrupt()
        }
    return AutoCloseable {
        watcher.cancel()
        signal(SIGINT, SIG_DFL)
    }
}
