package com.moneymanager.terminal

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import sun.misc.Signal
import java.util.concurrent.atomic.AtomicInteger

private val logger = Logger.withTag("Interrupts")

// A SIGINT handler rather than a shutdown hook: a hook also runs on a normal exit or SIGTERM, and once it runs
// the JVM is already shutting down, so a second Ctrl-C couldn't end it any sooner.
internal actual fun installInterruptHandler(
    scope: CoroutineScope,
    onInterrupt: () -> Unit,
): AutoCloseable {
    val interrupts = AtomicInteger()
    val signal = Signal("INT")
    val previous =
        try {
            Signal.handle(signal) {
                if (interrupts.getAndIncrement() == 0) onInterrupt() else Runtime.getRuntime().halt(INTERRUPTED_EXIT_CODE)
            }
        } catch (unavailable: IllegalArgumentException) {
            // The VM keeps SIGINT for itself (-Xrs): Ctrl-C ends the process the default way.
            logger.i(unavailable) { "Ctrl-C can't be handled; it will end mm without a summary" }
            return AutoCloseable {}
        } catch (unavailable: LinkageError) {
            // A runtime image without jdk.unsupported.
            logger.i(unavailable) { "Ctrl-C can't be handled; it will end mm without a summary" }
            return AutoCloseable {}
        }
    return AutoCloseable { Signal.handle(signal, previous) }
}
