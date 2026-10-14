package com.moneymanager.cli

import com.github.ajalt.mordant.rendering.OverflowWrap
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal enum class OutputFormat { JSON, TABLE }

/** Prints a command's result to stdout, as JSON or as a table for a person. */
internal class Output(
    private val terminal: Terminal,
    val format: OutputFormat,
) {
    fun <T> emit(
        value: T,
        serializer: KSerializer<T>,
        table: (T) -> Widget,
    ) {
        when (format) {
            OutputFormat.JSON -> terminal.rawPrint(json.encodeToString(serializer, value) + "\n")
            OutputFormat.TABLE -> terminal.println(table(value))
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
