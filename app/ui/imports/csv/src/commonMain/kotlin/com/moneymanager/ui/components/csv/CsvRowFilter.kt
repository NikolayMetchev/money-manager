package com.moneymanager.ui.components.csv

import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus

/**
 * The values a person has ticked per column: a column with nothing ticked doesn't filter, and a row stays
 * when, in every filtered column, its value is one of the ticked ones.
 */
internal data class CsvRowFilter(
    val columns: Map<Int, Set<String>> = emptyMap(),
    val statuses: Set<ImportStatus> = emptySet(),
) {
    val isActive: Boolean get() = statuses.isNotEmpty() || columns.values.any { it.isNotEmpty() }

    fun withColumn(
        columnIndex: Int,
        values: Set<String>,
    ): CsvRowFilter = copy(columns = if (values.isEmpty()) columns - columnIndex else columns + (columnIndex to values))

    fun matches(row: CsvRow): Boolean =
        (statuses.isEmpty() || row.importStatus in statuses) &&
            columns.all { (index, values) -> values.isEmpty() || row.values.getOrElse(index) { "" } in values }

    fun apply(rows: List<CsvRow>): List<CsvRow> = if (isActive) rows.filter(::matches) else rows

    companion object {
        /** The filter keeping, in each of [columns] named in [values], the rows with one of its values; unknown names are ignored. */
        fun byColumnName(
            columns: List<CsvColumn>,
            values: Map<String, Set<String>>,
        ): CsvRowFilter =
            CsvRowFilter(
                columns =
                    columns
                        .mapNotNull { column -> values[column.originalName]?.takeIf { it.isNotEmpty() }?.let { column.columnIndex to it } }
                        .toMap(),
            )
    }
}

/** The distinct values of one column, in order, for its filter menu. */
internal fun distinctColumnValues(
    rows: List<CsvRow>,
    columnIndex: Int,
): List<String> =
    rows
        .map { it.values.getOrElse(columnIndex) { "" } }
        .distinct()
        .sorted()

internal fun ImportStatus.label(): String =
    when (this) {
        ImportStatus.IMPORTED -> "New"
        ImportStatus.DUPLICATE -> "Duplicate"
        ImportStatus.UPDATED -> "Updated"
        ImportStatus.ERROR -> "Error"
        ImportStatus.SKIPPED -> "Skipped"
    }
