package com.moneymanager.domain.model.qif

/**
 * The fixed column names QIF records are presented as, so QIF can reuse the CSV strategy engine.
 * Domain vocabulary (db-free), so both the database (which seeds the built-in QIF strategy) and the
 * QIF importer can reference the same names.
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
