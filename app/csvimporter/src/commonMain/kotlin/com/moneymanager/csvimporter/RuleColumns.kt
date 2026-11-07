package com.moneymanager.csvimporter

import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig

/**
 * Each column's index by name: the file's [columns], then the columns [config]'s rules set that the file lacks,
 * after the last one. A row is shorter than that index, so such a column reads as absent until a rule sets it.
 */
internal fun columnIndexWithRuleColumns(
    columns: List<CsvColumn>,
    config: CsvStrategyConfig<*>,
): Map<String, Int> {
    val index = columns.associate { it.originalName to it.columnIndex }.toMutableMap()
    var next = (columns.maxOfOrNull { it.columnIndex } ?: -1) + 1
    for (name in config.ruleColumns()) {
        if (name !in index) index[name] = next++
    }
    return index
}
