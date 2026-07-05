package com.moneymanager.csvimporter

import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy

/** Number of leading rows sampled when content-scoring a file against a strategy. */
const val STRATEGY_CONTENT_SAMPLE_SIZE = 50

/**
 * Ranks a scored strategy `(strategy, contentScore)`: highest score first, ties broken
 * deterministically by name then id. Shared by the CSV ([selectForCsv]) and QIF
 * (`selectForQifContent`) selectors so both resolve a tie the same way.
 */
val byContentScoreThenNameThenId: Comparator<Pair<CsvImportStrategy, Int>> =
    compareByDescending<Pair<CsvImportStrategy, Int>> { it.second }
        .thenBy { it.first.name }
        .thenBy { it.first.id.toString() }

/**
 * Counts sampled rows with a value matching any of this strategy's
 * [CsvImportStrategy.contentMatchRules] (case-insensitive regex per named column). Shared by the
 * CSV selector below and the QIF selector. A strategy with no content rules scores 0.
 */
fun CsvImportStrategy.contentScore(
    sample: List<CsvRow>,
    columnIndexByName: Map<String, Int>,
): Int {
    if (contentMatchRules.isEmpty()) return 0
    val compiled = contentMatchRules.map { it.columnName to Regex(it.pattern, RegexOption.IGNORE_CASE) }
    return sample.count { row ->
        compiled.any { (columnName, regex) ->
            val idx = columnIndexByName[columnName] ?: return@any false
            row.values.getOrNull(idx)?.let { regex.containsMatchIn(it) } == true
        }
    }
}

/**
 * Picks the strategy for a staged CSV file. Column matching alone cannot distinguish sources whose
 * exports share one column set (e.g. crypto.com's card_/fiat_/crypto_ files), so candidates that
 * pass the exact column match are ranked by stronger signals:
 *
 * 1. **Filename**: candidates whose [CsvImportStrategy.fileNamePattern] matches [fileName] win
 *    outright (the export's own name is authoritative; content rules exist for renamed files).
 *    Among several, the best content score (then name, then id) breaks the tie.
 * 2. **Content**: candidates are scored via [contentScore] over the first
 *    [STRATEGY_CONTENT_SAMPLE_SIZE] of [rows]; only scores covering at least half the sample count.
 *    The threshold keeps a file that merely *contains* a few matching rows (e.g. crypto.com's
 *    crypto_ file carrying the odd viban_purchase) from being misrouted — such files are skipped.
 * 3. **Fallback**: a candidate carrying no signals at all (no content rules, no filename pattern)
 *    matches on columns alone — the pre-existing behavior for single-strategy column sets.
 *
 * Returns null when nothing matches; bulk import reports such files as skipped rather than
 * importing them with the wrong strategy.
 */
fun List<CsvImportStrategy>.selectForCsv(
    fileName: String,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
): CsvImportStrategy? {
    val headings = columns.map { it.originalName }.toSet()
    val candidates = filter { it.matchesColumns(headings) }
    if (candidates.isEmpty()) return null

    val indexByName = columns.associate { it.originalName to it.columnIndex }
    val sample = rows.take(STRATEGY_CONTENT_SAMPLE_SIZE)

    val fileNameMatches = candidates.filter { it.matchesFileName(fileName) }
    if (fileNameMatches.isNotEmpty()) {
        return fileNameMatches
            .map { it to it.contentScore(sample, indexByName) }
            .minWithOrNull(byContentScoreThenNameThenId)
            ?.first
    }

    val minScore = maxOf(1, (sample.size + 1) / 2)
    val contentMatch =
        candidates
            .map { it to it.contentScore(sample, indexByName) }
            .filter { it.second >= minScore }
            .minWithOrNull(byContentScoreThenNameThenId)
    if (contentMatch != null) return contentMatch.first

    return candidates
        .filter { it.contentMatchRules.isEmpty() && it.fileNamePattern.isNullOrBlank() }
        .minWithOrNull(compareBy({ it.name }, { it.id.toString() }))
}

private fun CsvImportStrategy.matchesFileName(fileName: String): Boolean {
    val pattern = fileNamePattern?.takeIf { it.isNotBlank() } ?: return false
    // A malformed user-entered pattern must not break selection for every other strategy.
    return runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(fileName) }
        .getOrDefault(false)
}
