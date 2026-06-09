package com.moneymanager.qif

/**
 * The type of a QIF section, derived from its `!`-header line.
 *
 * Banking sections ([BANK], [CASH], [CCARD], [OTH_A], [OTH_L]) contain importable
 * transactions. [INVESTMENT] records are parsed for display but not imported in v1.
 * The remaining list sections ([ACCOUNT_LIST], [CATEGORY_LIST], [CLASS_LIST],
 * [MEMORIZED]) and [UNKNOWN] are not importable as transactions.
 */
enum class QifSectionType(
    val isBankingTransaction: Boolean,
) {
    BANK(isBankingTransaction = true),
    CASH(isBankingTransaction = true),
    CCARD(isBankingTransaction = true),
    OTH_A(isBankingTransaction = true),
    OTH_L(isBankingTransaction = true),
    INVESTMENT(isBankingTransaction = false),
    ACCOUNT_LIST(isBankingTransaction = false),
    CATEGORY_LIST(isBankingTransaction = false),
    CLASS_LIST(isBankingTransaction = false),
    MEMORIZED(isBankingTransaction = false),
    UNKNOWN(isBankingTransaction = false),
    ;

    companion object {
        /**
         * Maps a QIF header line (e.g. `!Type:Bank`, `!Account`) to a [QifSectionType].
         * Matching is case-insensitive and tolerant of surrounding whitespace.
         */
        fun fromHeader(header: String): QifSectionType {
            val normalized = header.trim().removePrefix("!").trim()
            val typeValue = normalized.substringAfter("Type:", missingDelimiterValue = "").trim()
            return when {
                normalized.equals("Account", ignoreCase = true) -> ACCOUNT_LIST
                typeValue.equals("Bank", ignoreCase = true) -> BANK
                typeValue.equals("Cash", ignoreCase = true) -> CASH
                typeValue.equals("CCard", ignoreCase = true) -> CCARD
                typeValue.equals("Oth A", ignoreCase = true) -> OTH_A
                typeValue.equals("Oth L", ignoreCase = true) -> OTH_L
                typeValue.equals("Invst", ignoreCase = true) -> INVESTMENT
                typeValue.equals("Cat", ignoreCase = true) -> CATEGORY_LIST
                typeValue.equals("Class", ignoreCase = true) -> CLASS_LIST
                typeValue.equals("Memorized", ignoreCase = true) -> MEMORIZED
                else -> UNKNOWN
            }
        }
    }
}
