package com.moneymanager.domain.model.qif

/**
 * The fixed columns a QIF file is staged as (one row per transaction, or per split), so a QIF import is a CSV
 * import like any other. Domain vocabulary (db-free), so the built-in QIF strategies and the staging code
 * name the same columns.
 */
object QifColumns {
    const val COL_DATE = "Date"
    const val COL_AMOUNT = "Amount"
    const val COL_PAYEE = "Payee"
    const val COL_MEMO = "Memo"
    const val COL_CATEGORY = "Category"
    const val COL_TRANSFER_ACCOUNT = "Transfer Account"
    const val COL_CHECK_NUMBER = "Check Number"
    const val COL_CLEARED = "Cleared"
    const val COL_ACCOUNT = "Account"

    /** Fixed column headers, in order, presented to the CSV engine. */
    val headers: List<String> =
        listOf(
            COL_DATE,
            COL_AMOUNT,
            COL_PAYEE,
            COL_MEMO,
            COL_CATEGORY,
            COL_TRANSFER_ACCOUNT,
            COL_CHECK_NUMBER,
            COL_CLEARED,
            COL_ACCOUNT,
        )
}
