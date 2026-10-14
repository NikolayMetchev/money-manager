package com.moneymanager.terminal

import androidx.compose.runtime.BroadcastFrameClock
import com.jakewharton.mosaic.Mosaic
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.Event
import com.jakewharton.mosaic.terminal.Terminal
import com.moneymanager.tasks.TaskManager
import com.moneymanager.tasks.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Shows a [TaskManager]'s tasks on stderr while a command runs: live bars ([ProgressMode.LIVE]), plain lines
 * ([ProgressMode.PLAIN]) or nothing ([ProgressMode.OFF]). Only tasks started during [render] are shown.
 *
 * Mosaic lays the bars out ([TaskProgressView]), but this class owns the output. Mosaic's own runner draws
 * to stdout, which carries the command's JSON, and puts the terminal in raw mode, which would break the
 * password prompts a run can need half way through. Here each frame is composed straight to text and drawn
 * into the [region] on stderr, where prompts and messages can pause it or print above it.
 */
class TaskProgressRenderer(
    private val mode: ProgressMode,
    private val region: StderrRegion,
    /** The terminal's width in columns, read every frame so a resize takes effect at once. */
    private val width: () -> Int,
    // Bars only run where colours are welcome (NO_COLOR means plain lines), and the few colours they use are
    // in every terminal's basic palette.
    private val ansiLevel: AnsiLevel = AnsiLevel.ANSI16,
    private val interrupts: Interrupts = Interrupts.System,
    private val frameInterval: Duration = 100.milliseconds,
    private val plainInterval: Duration = 1.seconds,
) {
    /**
     * Runs [block], showing the tasks it starts on [manager] until it returns, then leaves their summary on
     * screen. Ctrl-C cancels those tasks and [block], waits for the tasks to wind down, shows the summary and
     * throws [TasksInterruptedException].
     */
    suspend fun <T> render(
        manager: TaskManager,
        block: suspend () -> T,
    ): T {
        if (mode == ProgressMode.OFF) return block()
        val sinceId = (manager.tasks.value.maxOfOrNull { it.id } ?: 0L) + 1
        return supervisorScope {
            val interrupted = MutableStateFlow(false)
            val handle = interrupts.install(this) { interrupted.value = true }
            val display = if (mode == ProgressMode.LIVE) LiveDisplay(this, sinceId) else PlainDisplay(sinceId)
            val displayJob = launch { display.run(manager) }
            // A supervisor scope, so a failing block surfaces from await() rather than cancelling the display.
            val work = async { block() }
            val stopper =
                launch {
                    interrupted.first { it }
                    manager.cancelSince(sinceId)
                    work.cancel()
                }
            try {
                work.await()
            } catch (cancelled: CancellationException) {
                if (!interrupted.value) throw cancelled
                withTimeoutOrNull(SETTLE_TIMEOUT) { manager.tasks.settled(sinceId) }
                throw TasksInterruptedException()
            } finally {
                withContext(NonCancellable) {
                    stopper.cancel()
                    handle.close()
                    displayJob.cancelAndJoin()
                    display.finish(manager.tasks.value)
                }
            }
        }
    }

    private interface Display {
        suspend fun run(manager: TaskManager)

        fun finish(tasks: List<TaskState>)
    }

    private inner class LiveDisplay(
        scope: CoroutineScope,
        private val sinceId: Long,
    ) : Display {
        private var latest = ""
        private var drawing = true

        private val mosaic =
            Mosaic(
                // Mosaic needs a frame clock in its context. This one never ticks: each frame is a fresh
                // setContent, which composes and draws synchronously.
                coroutineContext = scope.coroutineContext + BroadcastFrameClock(),
                onDraw = { mosaic ->
                    latest = mosaic.draw().render(ansiLevel, false)
                    if (drawing) region.draw(latest)
                },
                terminal = StderrTerminal(ansiLevel),
            )

        override suspend fun run(manager: TaskManager) {
            var frame = 0
            while (true) {
                compose(manager.tasks.value, frame++)
                delay(frameInterval)
            }
        }

        override fun finish(tasks: List<TaskState>) {
            drawing = false
            compose(tasks, frame = 0)
            mosaic.cancel()
            region.close(latest)
        }

        private fun compose(
            tasks: List<TaskState>,
            frame: Int,
        ) {
            val columns = width()
            mosaic.setContent { TaskProgressView(tasks, columns, sinceId, frame) }
        }
    }

    private inner class PlainDisplay(
        sinceId: Long,
    ) : Display {
        private val log = PlainTaskLog(sinceId) { region.printAbove(it + "\n") }

        override suspend fun run(manager: TaskManager) {
            while (true) {
                log.update(manager.tasks.value)
                delay(plainInterval)
            }
        }

        override fun finish(tasks: List<TaskState>) = log.update(tasks)
    }

    private companion object {
        val SETTLE_TIMEOUT = 10.seconds
    }
}

private fun TaskManager.cancelSince(sinceId: Long) =
    tasks.value
        .filter { it.parentId == null && it.id >= sinceId && it.isRunning }
        .forEach { cancel(it.id) }

private suspend fun StateFlow<List<TaskState>>.settled(sinceId: Long) = first { tasks -> tasks.none { it.id >= sinceId && it.isRunning } }

/** What Mosaic is told about the terminal: a plain output with no input, since the CLI keeps stdin for prompts. */
private class StderrTerminal(
    override val ansiLevel: AnsiLevel,
) : Terminal,
    Terminal.State,
    Terminal.Capabilities {
    override val name: String? get() = null
    override val interactive: Boolean get() = false
    override val state: Terminal.State get() = this
    override val capabilities: Terminal.Capabilities get() = this
    override val events: ReceiveChannel<Event> = Channel<Event>().apply { close() }
    override val focused: StateFlow<Boolean> = MutableStateFlow(true)
    override val theme: StateFlow<Terminal.Theme> = MutableStateFlow(Terminal.Theme.Unknown)
    override val size: StateFlow<Terminal.Size> = MutableStateFlow(Terminal.Size.Default)
    override val cursorVisibility: Boolean get() = false
    override val focusEvents: Boolean get() = false
    override val inBandResizeEvents: Boolean get() = false
    override val kittyGraphics: Boolean get() = false
    override val kittyKeyboard: Boolean get() = false
    override val kittyNotifications: Boolean get() = false
    override val kittyPointerShape: Boolean get() = false
    override val kittyTextSizingScale: Boolean get() = false
    override val kittyTextSizingWidth: Boolean get() = false
    override val kittyUnderline: Boolean get() = false
    override val synchronizedOutput: Boolean get() = false
    override val themeEvents: Boolean get() = false

    override fun close() = Unit
}
