package com.moneymanager.cli

import com.github.ajalt.mordant.rendering.OverflowWrap
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.moneymanager.query.Envelope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal enum class OutputFormat { JSON, TABLE }

/**
 * Prints a command's result to stdout, as JSON in an [Envelope] (the documented contract, see
 * `docs/cli-json.md`) or as a table for a person.
 */
internal class Output(
    private val terminal: Terminal,
    val format: OutputFormat,
    /** Where a table's notes go (stderr), so stdout stays just the table. */
    private val note: (String) -> Unit,
) {
    /** [nextCursor] says there is another page; a table run tells the person how to fetch it. */
    fun <T> emit(
        value: T,
        serializer: KSerializer<T>,
        nextCursor: String? = null,
        table: (T) -> Widget,
    ) {
        when (format) {
            OutputFormat.JSON -> {
                val envelope = Envelope.of(value, nextCursor)
                terminal.rawPrint(json.encodeToString(Envelope.serializer(serializer), envelope) + "\n")
            }
            OutputFormat.TABLE -> {
                terminal.println(table(value))
                nextCursor?.let { note("More: rerun with --cursor $it") }
            }
        }
    }

    private companion object {
        val json = Json { prettyPrint = true }
    }
}

/** A two-column table of labelled values, for results that are one record. */
internal fun keyValueTable(rows: List<Pair<String, String>>): Widget =
    table {
        // Labels keep their width (Auto is sized before Expand); long values such as paths wrap instead.
        column(1) {
            width = ColumnWidth.Expand()
            whitespace = Whitespace.NORMAL
            overflowWrap = OverflowWrap.BREAK_WORD
        }
        body { rows.forEach { (key, value) -> row(key, value) } }
    }
