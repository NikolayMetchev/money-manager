package com.moneymanager.ui.screens.csvstrategy

import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow

/**
 * Utility object for auto-detecting likely column mappings based on column names and values.
 *
 * Detection strategy (in order of priority):
 * 1. Exact word match in column name (e.g., "Date" matches "date")
 * 2. Value-based detection (e.g., column contains date-like values)
 * 3. Substring match in column name (fallback)
 */
object ColumnDetector {
    // Name patterns ordered by specificity
    private val dateNamePatterns = listOf("date", "posted", "when", "day")
    private val timeNamePatterns = listOf("time")
    private val amountNamePatterns =
        listOf("amount", "value", "sum", "debit", "credit", "money", "price", "cost", "total")
    private val descriptionNamePatterns =
        listOf("description", "memo", "narrative", "details", "reference", "particular", "note", "remark")
    private val payeeNamePatterns =
        listOf("payee", "name", "merchant", "counterparty", "beneficiary", "vendor", "recipient", "payer", "party")
    private val currencyNamePatterns = listOf("currency", "ccy", "curr", "fx")

    // Value patterns for content-based detection
    private val dateValuePatterns =
        listOf(
            // DD/MM/YYYY, DD-MM-YYYY, DD.MM.YYYY
            Regex("""\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4}"""),
            // YYYY-MM-DD, YYYY/MM/DD
            Regex("""\d{4}[/\-]\d{1,2}[/\-]\d{1,2}"""),
            // Month name formats: "24 Feb 2022", "Feb 24, 2022"
            Regex("""\d{1,2}\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\w*\s+\d{2,4}""", RegexOption.IGNORE_CASE),
            Regex("""(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\w*\s+\d{1,2},?\s+\d{2,4}""", RegexOption.IGNORE_CASE),
        )

    // Time values: HH:mm or HH:mm:ss format
    private val timeValuePattern = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")

    // Amount values: numbers with optional decimal, negative sign, currency symbols
    private val amountValuePattern = Regex("""^[£$€¥]?-?\d{1,3}(,\d{3})*(\.\d{1,2})?$|^-?\d+(\.\d{1,2})?$""")

    // Currency values: 3-letter ISO 4217 codes
    private val currencyValuePattern = Regex("""^[A-Z]{3}$""")

    /**
     * Checks if a column name matches a pattern as a whole word.
     */
    private fun matchesNameAsWord(
        columnName: String,
        pattern: String,
    ): Boolean {
        val regex = Regex("\\b${Regex.escape(pattern)}\\b", RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(columnName)
    }

    /**
     * Checks if a value looks like a date.
     */
    private fun looksLikeDate(value: String): Boolean = dateValuePatterns.any { it.containsMatchIn(value.trim()) }

    /**
     * Checks if a value looks like an amount.
     */
    private fun looksLikeAmount(value: String): Boolean = amountValuePattern.matches(value.trim())

    /**
     * Checks if a value looks like a time (HH:mm or HH:mm:ss format).
     */
    private fun looksLikeTime(value: String): Boolean = timeValuePattern.matches(value.trim())

    /**
     * Checks if a value looks like an ISO 4217 currency code.
     */
    private fun looksLikeCurrency(value: String): Boolean = currencyValuePattern.matches(value.trim().uppercase())

    /**
     * Suggests a column based on name patterns and optionally value analysis.
     */
    private fun suggestColumn(
        columns: List<CsvColumn>,
        namePatterns: List<String>,
        sampleValues: Map<Int, String>? = null,
        valueMatcher: ((String) -> Boolean)? = null,
    ): String? {
        // First pass: exact word match in column name
        for (pattern in namePatterns) {
            val match = columns.find { matchesNameAsWord(it.originalName, pattern) }
            if (match != null) return match.originalName
        }

        // Second pass: value-based detection (if sample values provided)
        if (sampleValues != null && valueMatcher != null) {
            val match =
                columns.find { col ->
                    sampleValues[col.columnIndex]?.let { valueMatcher(it) } == true
                }
            if (match != null) return match.originalName
        }

        // Third pass: substring match in column name (fallback)
        for (pattern in namePatterns) {
            val match = columns.find { it.originalName.contains(pattern, ignoreCase = true) }
            if (match != null) return match.originalName
        }

        return null
    }

    fun suggestDateColumn(
        columns: List<CsvColumn>,
        sampleValues: Map<Int, String>? = null,
    ): String? = suggestColumn(columns, dateNamePatterns, sampleValues, ::looksLikeDate)

    fun suggestAmountColumn(
        columns: List<CsvColumn>,
        sampleValues: Map<Int, String>? = null,
    ): String? = suggestColumn(columns, amountNamePatterns, sampleValues, ::looksLikeAmount)

    fun suggestTimeColumn(
        columns: List<CsvColumn>,
        sampleValues: Map<Int, String>? = null,
    ): String? = suggestColumn(columns, timeNamePatterns, sampleValues, ::looksLikeTime)

    fun suggestDescriptionColumn(columns: List<CsvColumn>): String? = suggestColumn(columns, descriptionNamePatterns)

    fun suggestPayeeColumn(columns: List<CsvColumn>): String? = suggestColumn(columns, payeeNamePatterns)

    fun suggestCurrencyColumn(
        columns: List<CsvColumn>,
        sampleValues: Map<Int, String>? = null,
    ): String? = suggestColumn(columns, currencyNamePatterns, sampleValues, ::looksLikeCurrency)

    // Columns that are unsuitable for account name fallbacks (IDs, dates, amounts, etc.)
    private val excludedFallbackPatterns = listOf("id", "date", "time", "amount", "currency", "money")

    // Preferred fallback column names (semantic columns that describe transaction type)
    private val preferredFallbackPatterns = listOf("type", "category", "kind", "transaction type")

    /**
     * Checks if a column name should be excluded from fallback consideration.
     */
    private fun isExcludedForFallback(columnName: String): Boolean =
        excludedFallbackPatterns.any { pattern ->
            columnName.contains(pattern, ignoreCase = true)
        }

    /**
     * Checks if a column name is a preferred fallback column.
     */
    private fun isPreferredFallback(columnName: String): Boolean =
        preferredFallbackPatterns.any { pattern ->
            columnName.equals(pattern, ignoreCase = true) ||
                columnName.contains(pattern, ignoreCase = true)
        }

    /**
     * Detects fallback columns for the target account.
     * Finds rows where the primary column is blank and identifies
     * which other columns consistently have values in those rows.
     *
     * Excludes columns that are unsuitable for account names (IDs, dates, amounts)
     * and prefers semantic columns like "Type" or "Category".
     *
     * @param primaryColumn The primary column name for target account lookup
     * @param columns The available CSV columns
     * @param rows All CSV rows to analyze
     * @return List of fallback column names, ordered by preference (best first)
     */
    fun suggestFallbackColumns(
        primaryColumn: String,
        columns: List<CsvColumn>,
        rows: List<CsvRow>,
    ): List<String> {
        val primaryIndex =
            columns.find { it.originalName == primaryColumn }?.columnIndex
                ?: return emptyList()

        // Find rows where primary column is blank
        val rowsWithBlankPrimary =
            rows.filter { row ->
                row.values.getOrNull(primaryIndex)?.isBlank() == true
            }

        if (rowsWithBlankPrimary.isEmpty()) return emptyList()

        // For each other column, count how many blank-primary rows have a value
        // Exclude columns unsuitable for account names
        val candidateColumns =
            columns
                .filter { it.originalName != primaryColumn }
                .filter { !isExcludedForFallback(it.originalName) }
                .map { col ->
                    val filledCount =
                        rowsWithBlankPrimary.count { row ->
                            row.values.getOrNull(col.columnIndex)?.isNotBlank() == true
                        }
                    Triple(col.originalName, filledCount, isPreferredFallback(col.originalName))
                }
                .filter { (_, count, _) -> count > 0 }
                // Sort by: preferred columns first, then by coverage
                .sortedWith(
                    compareByDescending<Triple<String, Int, Boolean>> { (_, _, preferred) -> preferred }
                        .thenByDescending { (_, count, _) -> count },
                )
                .map { (name, _, _) -> name }

        // Return top candidate(s) - typically just the best one
        return candidateColumns.take(1)
    }
}

/**
 * Helper function to get sample value from first row for a given column.
 */
internal fun getSampleValue(
    columns: List<CsvColumn>,
    firstRow: CsvRow?,
    columnName: String?,
): String? {
    if (columnName == null || firstRow == null) return null
    val columnIndex = columns.find { it.originalName == columnName }?.columnIndex ?: return null
    return firstRow.values.getOrNull(columnIndex)
}

/**
 * Finds the first row where the specified column is blank.
 * Used to find a representative sample for fallback columns.
 */
internal fun findRowWithBlankColumn(
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    columnName: String?,
): CsvRow? {
    if (columnName == null) return null
    val columnIndex = columns.find { it.originalName == columnName }?.columnIndex ?: return null
    return rows.find { row ->
        row.values.getOrNull(columnIndex)?.isBlank() == true
    }
}
