package com.moneymanager.cli

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.github.ajalt.mordant.terminal.Terminal
import com.moneymanager.files.appendText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock

/**
 * Routes Kermit for a CLI run. Logs never touch stdout, which carries the command's result: errors (or
 * everything with [verbose]) go to stderr, unless [quiet]; Info and above go to [logFile] as well.
 */
internal fun installCliLogging(
    terminal: Terminal,
    verbose: Boolean,
    quiet: Boolean,
    logFile: Path?,
) {
    val stderrSeverity = if (verbose) Severity.Debug else Severity.Error
    val writers =
        buildList {
            if (!quiet) add(StderrLogWriter(terminal, stderrSeverity))
            logFile?.let { add(FileLogWriter(it)) }
        }
    Logger.setLogWriters(writers)
    Logger.setMinSeverity(if (logFile != null) minOf(stderrSeverity, Severity.Info) else stderrSeverity)
}

private class StderrLogWriter(
    private val terminal: Terminal,
    private val minSeverity: Severity,
) : LogWriter() {
    override fun isLoggable(
        tag: String,
        severity: Severity,
    ): Boolean = severity >= minSeverity

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        val cause = throwable?.let { ": $it" }.orEmpty()
        terminal.rawPrint("${severity.name.lowercase()}: $message$cause\n", stderr = true)
    }
}

/** Appends to [file], rolling it over to `<name>.1` once it passes [MAX_LOG_BYTES] at startup. */
private class FileLogWriter(
    private val file: Path,
) : LogWriter() {
    init {
        runCatching {
            val size = SystemFileSystem.metadataOrNull(file)?.size ?: 0
            if (size > MAX_LOG_BYTES) SystemFileSystem.atomicMove(file, Path(file.parent ?: Path("."), "${file.name}.1"))
        }
    }

    override fun isLoggable(
        tag: String,
        severity: Severity,
    ): Boolean = severity >= Severity.Info

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        val line =
            buildString {
                append(Clock.System.now())
                    .append(' ')
                    .append(severity.name.uppercase())
                    .append(" [")
                    .append(tag)
                    .append("] ")
                append(message).append('\n')
                throwable?.let { append(it.stackTraceToString()).append('\n') }
            }
        // A log line must never fail the command (read-only home, full disk).
        runCatching { appendText(file, line) }
    }
}

private const val MAX_LOG_BYTES = 10L * 1024 * 1024
