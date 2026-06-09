package com.moneymanager.qif

/**
 * Configuration options for QIF parsing.
 *
 * QIF date and amount interpretation (format, sign convention) is intentionally left to
 * the import strategy rather than the parser, so the parser keeps field values as raw
 * strings.
 *
 * @property trimValues Whether to trim surrounding whitespace from each field value (default: true).
 */
data class QifParseOptions(
    val trimValues: Boolean = true,
)
