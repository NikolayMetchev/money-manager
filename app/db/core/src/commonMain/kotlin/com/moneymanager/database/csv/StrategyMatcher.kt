package com.moneymanager.database.csv

import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy

/**
 * Matches CSV column headings to import strategies.
 */
object StrategyMatcher {
    /**
     * Finds the first strategy that matches the given CSV column headings.
     * Matching is exact and order-independent.
     *
     * @param csvHeadings The column headings from the CSV file
     * @param strategies The available import strategies
     * @return The matching strategy, or null if no match found
     */
    fun findMatchingStrategy(
        csvHeadings: Collection<String>,
        strategies: Collection<CsvImportStrategy>,
    ): CsvImportStrategy? {
        val headingsSet = csvHeadings.toSet()
        return strategies.find { it.matchesColumns(headingsSet) }
    }

    /**
     * Finds all strategies that match the given CSV column headings.
     * Matching is exact and order-independent.
     *
     * @param csvHeadings The column headings from the CSV file
     * @param strategies The available import strategies
     * @return List of matching strategies (may be empty)
     */
    fun findAllMatchingStrategies(
        csvHeadings: Collection<String>,
        strategies: Collection<CsvImportStrategy>,
    ): List<CsvImportStrategy> {
        val headingsSet = csvHeadings.toSet()
        return strategies.filter { it.matchesColumns(headingsSet) }
    }
}
