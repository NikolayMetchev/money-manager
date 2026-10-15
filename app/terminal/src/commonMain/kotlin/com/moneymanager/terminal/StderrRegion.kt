package com.moneymanager.terminal

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A block of lines at the bottom of the terminal that redraws in place (the live progress bars), plus the
 * one way to write anything else to stderr while it's showing. Everything that writes to stderr during a
 * live run goes through here: a message is printed above the region ([printAbove]), and a prompt clears it
 * for as long as the person is typing ([paused]). Otherwise a log line would land in the middle of the
 * bars and the next redraw would erase it.
 *
 * Draws come from the renderer's coroutine while messages and prompts come from task threads, so every
 * write happens under one lock. With no live frame every call is a plain write.
 */
class StderrRegion(
    private val write: (String) -> Unit,
) {
    private val lock = SynchronizedObject()
    private var frame: String? = null
    private var drawnHeight = 0
    private var pauses = 0

    /** Replaces the region with [next] (lines separated by `\n`), unless a prompt is showing. */
    fun draw(next: String) =
        synchronized(lock) {
            val starting = frame == null
            frame = next
            if (pauses == 0) write((if (starting) HIDE_CURSOR else "") + erase() + drawn(next))
        }

    /** Writes [text] above the region, then redraws the region below it. */
    fun printAbove(text: String) =
        synchronized(lock) {
            val current = frame
            if (current == null || pauses > 0) {
                write(text)
            } else {
                write(erase() + text.withTrailingNewline() + drawn(current))
            }
        }

    /** Clears the region while [block] runs (a prompt the person answers), then draws it again. */
    fun <T> paused(block: () -> T): T {
        synchronized(lock) {
            if (pauses++ == 0 && frame != null) write(erase() + SHOW_CURSOR)
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                if (--pauses == 0) frame?.let { write(HIDE_CURSOR + drawn(it)) }
            }
        }
    }

    /** Ends the live region, leaving [last] on screen as the summary (or nothing when null). */
    fun close(last: String?) =
        synchronized(lock) {
            if (frame == null) {
                last?.let { write(drawn(it)) }
            } else {
                write(erase() + (last?.let(::drawn) ?: "") + SHOW_CURSOR)
            }
            frame = null
            drawnHeight = 0
        }

    // Moves to the first line the region drew and clears from there down.
    private fun erase(): String {
        if (drawnHeight == 0) return ""
        val sequence = "$CSI${drawnHeight}F$CSI" + "J"
        drawnHeight = 0
        return sequence
    }

    private fun drawn(text: String): String {
        if (text.isEmpty()) return ""
        drawnHeight = text.count { it == '\n' } + 1
        return text + "\n"
    }

    private fun String.withTrailingNewline(): String = if (endsWith('\n')) this else this + "\n"

    internal companion object {
        const val CSI = "\u001b["
        const val HIDE_CURSOR = "$CSI?25l"
        const val SHOW_CURSOR = "$CSI?25h"
    }
}
