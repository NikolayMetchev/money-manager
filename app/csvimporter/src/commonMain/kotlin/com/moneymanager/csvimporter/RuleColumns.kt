package com.moneymanager.csvimporter

import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.FILE_NAME_COLUMN

/**
 * Each column's index by name: the file's [columns], then [FILE_NAME_COLUMN], then the columns [config]'s rules set
 * that the file lacks, after the last one. A row is shorter than those, so such a column reads as absent until a rule
 * sets it.
 */
internal fun columnIndexWithRuleColumns(
    columns: List<CsvColumn>,
    config: CsvStrategyConfig<*>,
): Map<String, Int> {
    val index = columns.associate { it.originalName to it.columnIndex }.toMutableMap()
    var next = (columns.maxOfOrNull { it.columnIndex } ?: -1) + 1
    for (name in listOf(FILE_NAME_COLUMN) + config.ruleColumns()) {
        if (name !in index) index[name] = next++
    }
    return index
}

/**
 * A row's cells with [fileName] in [FILE_NAME_COLUMN], at its place in [columnIndex] (see [columnIndexWithRuleColumns]).
 * A file with a column of that name keeps its own cell.
 */
internal fun List<String>.withFileName(
    columnIndex: Map<String, Int>,
    fileName: String?,
): List<String> {
    val at = columnIndex[FILE_NAME_COLUMN]
    if (fileName == null || at == null || at < size) return this
    return this + List(at - size) { "" } + fileName
}
