package com.moneymanager.csvimporter

import com.moneymanager.domain.model.rules.ColumnRecord
import com.moneymanager.domain.model.rules.RuleOutcome

internal class RowValues(
    private val cells: List<String>,
    private val index: Map<String, Int>,
    private val optionalColumns: Set<String>,
) {
    val record = ColumnRecord(cells, index)
    val trimmedRecord get() = ColumnRecord(cells.map { it.trim() }, index)

    fun required(column: String): String {
        require(record.exists(column) || column in optionalColumns) { "Column not found: $column" }
        return record.text(column).orEmpty()
    }
}

/** [original] as the rules left it: every cell a rule swapped or set rewritten (a short row padded). */
internal fun RuleOutcome.rewrittenValues(
    original: List<String>,
    columnIndexByName: Map<String, Int>,
): List<String> {
    if (record is ColumnRecord) return original
    val rewritten = original.toMutableList()
    for ((name, index) in columnIndexByName) {
        while (rewritten.size <= index) rewritten.add("")
        rewritten[index] = record.text(name).orEmpty()
    }
    return rewritten
}
