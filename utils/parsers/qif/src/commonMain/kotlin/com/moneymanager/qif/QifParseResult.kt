package com.moneymanager.qif

/**
 * Result of parsing a QIF file.
 *
 * @property sections The parsed sections, in file order.
 * @property unsupportedRecordCount Number of records that cannot be imported in v1
 *   (investment and unknown-section records).
 */
data class QifParseResult(
    val sections: List<QifSection>,
    val unsupportedRecordCount: Int,
) {
    /** All records across every section, in file order. */
    val records: List<QifRecord>
        get() = sections.flatMap { it.records }
}

/**
 * A single QIF section introduced by a `!`-header line.
 *
 * @property type The section type derived from the header.
 * @property accountName The owning account name, when known (from a preceding `!Account`
 *   block); null when the file declares no account.
 * @property records The records contained in this section.
 */
data class QifSection(
    val type: QifSectionType,
    val accountName: String?,
    val records: List<QifRecord>,
)

/**
 * A single QIF record (one transaction or list entry), terminated by a `^` line.
 *
 * @property recordIndex Global 0-based index across the whole file; used as the provenance key.
 * @property sectionType The type of the section this record belongs to.
 * @property accountName The owning account name, when known.
 * @property supported Whether this record is importable as a transaction in v1.
 * @property rawLines The verbatim lines of the record, including the trailing `^`.
 * @property fields The parsed fields.
 */
data class QifRecord(
    val recordIndex: Int,
    val sectionType: QifSectionType,
    val accountName: String?,
    val supported: Boolean,
    val rawLines: List<String>,
    val fields: QifFields,
)

/**
 * Parsed fields of a QIF record. Values are kept as raw strings; date and amount
 * interpretation is the import strategy's responsibility.
 *
 * @property date `D` field.
 * @property amount `T` field (or `U` as a fallback).
 * @property payee `P` field.
 * @property memo `M` field.
 * @property category `L` field when it is not a bracketed transfer account.
 * @property transferAccount `L` field when it is a `[bracketed]` transfer account (brackets stripped).
 * @property checkNumber `N` field (a check/reference number in banking sections).
 * @property clearedStatus `C` field.
 * @property investmentAction `N` field within an investment section (display only).
 * @property address `A` fields, in order (repeatable).
 * @property splits Split sub-records (`S`/`E`/`$`).
 * @property unknownFields Any field codes not otherwise recognised, preserved verbatim.
 */
data class QifFields(
    val date: String? = null,
    val amount: String? = null,
    val payee: String? = null,
    val memo: String? = null,
    val category: String? = null,
    val transferAccount: String? = null,
    val checkNumber: String? = null,
    val clearedStatus: String? = null,
    val investmentAction: String? = null,
    val address: List<String> = emptyList(),
    val splits: List<QifSplit> = emptyList(),
    val unknownFields: List<Pair<Char, String>> = emptyList(),
)

/**
 * A single split line group within a QIF record.
 *
 * @property category `S` field when it is not a bracketed transfer account.
 * @property transferAccount `S` field when it is a `[bracketed]` transfer account (brackets stripped).
 * @property memo `E` field.
 * @property amount `$` field.
 */
data class QifSplit(
    val category: String? = null,
    val transferAccount: String? = null,
    val memo: String? = null,
    val amount: String? = null,
)
