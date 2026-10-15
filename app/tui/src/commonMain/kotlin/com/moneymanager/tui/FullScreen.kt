package com.moneymanager.tui

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.Mosaic
import com.jakewharton.mosaic.TextCanvas
import com.jakewharton.mosaic.terminal.Event
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.tty.Tty
import com.jakewharton.mosaic.tty.terminal.asTerminalIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Thrown when there's no terminal to draw on: stdin or stdout is redirected. */
class NotATerminalException : IllegalStateException("The TUI needs a terminal: stdin and stdout must not be redirected")

/**
 * Runs [content] full screen until [body] returns: raw mode, the alternate screen, no cursor. However [body]
 * ends (returning, throwing, cancelled), the terminal is restored as it was, with the shell's screen back.
 *
 * Not Mosaic's own `runMosaic`: that draws inline below the prompt rather than full screen, and its key
 * translation throws on any key it has no name for (a non-ASCII letter, Ctrl-\), which would end the
 * composition mid-session. [KeyFilterTerminal] drops those keys instead.
 */
suspend fun runFullScreen(
    content: @Composable () -> Unit,
    body: suspend () -> Unit,
) = coroutineScope {
    val tty = Tty.tryBind() ?: throw NotATerminalException()
    try {
        val terminal = KeyFilterTerminal(tty.asTerminalIn(this), this)
        try {
            val screen = ScreenWriter(terminal) { tty.writeFully(it) }
            // Leaving is in a finally that also covers entering: a write failing halfway through entering
            // must still switch the alternate screen back off.
            try {
                screen.enter()
                val clock = BroadcastFrameClock()
                val mosaic =
                    Mosaic(
                        coroutineContext = coroutineContext + clock,
                        onDraw = { screen.draw(it.draw()) },
                        terminal = terminal,
                    )
                val frames =
                    launch {
                        val start = TimeSource.Monotonic.markNow()
                        while (true) {
                            clock.sendFrame(start.elapsedNow().inWholeNanoseconds)
                            delay(FRAME_INTERVAL)
                        }
                    }
                try {
                    mosaic.setContent(content)
                    body()
                } finally {
                    frames.cancel()
                    mosaic.cancel()
                }
            } finally {
                screen.leave()
            }
        } finally {
            terminal.close()
        }
    } finally {
        tty.close()
    }
}

private val FRAME_INTERVAL = 16.milliseconds

private fun Tty.writeFully(text: String) {
    val bytes = text.encodeToByteArray()
    var offset = 0
    while (offset < bytes.size) {
        val written = write(bytes, offset, bytes.size - offset)
        if (written <= 0) return
        offset += written
    }
}

/** Draws whole frames over the alternate screen, from the top-left corner, clipped to the terminal's rows. */
internal class ScreenWriter(
    private val terminal: Terminal,
    private val write: (String) -> Unit,
) {
    fun enter() = write("$CSI?1049h$CSI?25l$CSI${"H"}$CSI${"2J"}")

    fun leave() = write("$CSI?25h$CSI?1049l")

    fun draw(canvas: TextCanvas) {
        val capabilities = terminal.capabilities
        val screenRows = terminal.state.size.value.rows
        val rows =
            canvas
                .render(capabilities.ansiLevel, capabilities.kittyUnderline)
                .split('\n')
                .take(screenRows)
        write(frame(rows, screenRows, capabilities.synchronizedOutput))
    }

    companion object {
        /**
         * Each row is erased before it's written, not after: erasing to the end of the line once a row fills the
         * last column would wipe that column's cell. A newline after the screen's last row would scroll it, so
         * the rows below a short frame are cleared only when there are some.
         */
        fun frame(
            rows: List<String>,
            screenRows: Int,
            synchronized: Boolean,
        ): String =
            buildString {
                if (synchronized) append("$CSI?2026h")
                append("${CSI}H")
                rows.forEachIndexed { index, row ->
                    if (index > 0) append("\r\n")
                    append("${CSI}2K").append(row)
                }
                if (rows.size < screenRows) append("\r\n${CSI}J")
                if (synchronized) append("$CSI?2026l")
            }
    }
}

private const val CSI = "\u001b["

/**
 * [terminal], minus the key presses Mosaic can't name. Mosaic 0.18 throws on those rather than skipping them,
 * which would end the TUI at the first `é` typed into a prompt.
 *
 * Events move to an unbounded buffer as they arrive, on a thread of their own, rather than waiting for Mosaic
 * to take them once a frame: the TTY terminal keeps only the last 64. A paste of more than about 64 characters
 * can still lose some, because Mosaic's reader parses a burst faster than any consumer is scheduled.
 */
internal class KeyFilterTerminal(
    private val terminal: Terminal,
    scope: CoroutineScope,
) : Terminal by terminal {
    private val filtered = Channel<Event>(Channel.UNLIMITED)

    override val events: ReceiveChannel<Event> = filtered

    // The TTY terminal never closes its event channel, so this must be cancelled, or it outlives the TUI.
    private val forwarding =
        scope.launch(Dispatchers.Default) {
            try {
                for (event in terminal.events) {
                    if (event !is KeyboardEvent || isNamedKey(event.codepoint)) filtered.send(event)
                }
            } finally {
                filtered.close()
            }
        }

    override fun close() {
        forwarding.cancel()
        terminal.close()
    }

    companion object {
        /** The codepoints Mosaic's key translation names; see `KeyboardEvent.toKeyEventOrNull` in mosaic-runtime. */
        fun isNamedKey(codepoint: Int): Boolean =
            codepoint == TAB ||
                codepoint == ENTER ||
                codepoint == ESCAPE ||
                codepoint in PRINTABLE_ASCII ||
                codepoint == BACKSPACE ||
                codepoint in KeyboardEvent.Insert..KeyboardEvent.End ||
                codepoint in FUNCTION_KEYS

        private const val TAB = 9
        private const val ENTER = 13
        private const val ESCAPE = 27
        private const val BACKSPACE = 127
        private val PRINTABLE_ASCII = 32..126
        private val FUNCTION_KEYS = 57364..57398
    }
}
