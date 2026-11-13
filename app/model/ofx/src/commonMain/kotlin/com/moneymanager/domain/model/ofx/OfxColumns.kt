package com.moneymanager.domain.model.ofx

/**
 * The fixed columns an OFX file is staged as (one row per `<STMTTRN>` of every bank and credit-card statement in it),
 * so an OFX import is a CSV import like any other. Domain vocabulary (db-free), so the built-in OFX strategies and the
 * staging code name the same columns.
 *
 * OFX's own date-time syntax (`20240402134501.000[-7:MST]`) is the format's, not a source's, so staging splits it into
 * [COL_DATE] (`yyyy-MM-dd`), [COL_TIME] (`HH:mm:ss`, blank when the file gives none) and [COL_UTC_OFFSET] (`±HH:mm`,
 * blank when it names none); a strategy reads them with an ordinary date mapping. Every other value is the file's text.
 */
object OfxColumns {
    /** `<DTPOSTED>`'s date. */
    const val COL_DATE = "Date"

    /** `<DTPOSTED>`'s time of day. */
    const val COL_TIME = "Time"

    /** `<DTPOSTED>`'s offset from UTC. */
    const val COL_UTC_OFFSET = "UTC Offset"

    /** `<DTUSER>`'s date: when the transaction was made, where the posting date differs. */
    const val COL_USER_DATE = "User Date"

    /** `<TRNAMT>`, signed: negative takes money out of the statement's account. */
    const val COL_AMOUNT = "Amount"

    /** `<TRNTYPE>` (DEBIT, CREDIT, POS, ATM, FEE, …). */
    const val COL_TYPE = "Type"

    /** `<FITID>`: the institution's id for the transaction, unique within the account. */
    const val COL_FITID = "FITID"

    /** `<REFNUM>`. */
    const val COL_REFERENCE = "Reference"

    /** `<CHECKNUM>`. */
    const val COL_CHECK_NUMBER = "Check Number"

    /** `<NAME>`, else `<PAYEE><NAME>`. */
    const val COL_NAME = "Name"

    /** `<MEMO>`. */
    const val COL_MEMO = "Memo"

    /** The transaction's `<CURRENCY>`, else its statement's `<CURDEF>`. */
    const val COL_CURRENCY = "Currency"

    /** `<ORIGCURRENCY>`: the currency the transaction was originally made in. */
    const val COL_ORIGINAL_CURRENCY = "Original Currency"

    /** `<CURRATE>` of the transaction's currency or original currency. */
    const val COL_EXCHANGE_RATE = "Exchange Rate"

    /** The statement's `<ACCTID>`. */
    const val COL_ACCOUNT_ID = "Account ID"

    /** The statement's `<BANKID>` (blank on a credit-card statement). */
    const val COL_BANK_ID = "Bank ID"

    /** The statement's `<ACCTTYPE>`, `CREDITCARD` on a credit-card statement. */
    const val COL_ACCOUNT_TYPE = "Account Type"

    /** The sign-on's `<FI><ORG>`, when the file names its institution. */
    const val COL_INSTITUTION = "Institution"

    /** Fixed column headers, in order, presented to the CSV engine. */
    val headers: List<String> =
        listOf(
            COL_DATE,
            COL_TIME,
            COL_UTC_OFFSET,
            COL_USER_DATE,
            COL_AMOUNT,
            COL_TYPE,
            COL_FITID,
            COL_REFERENCE,
            COL_CHECK_NUMBER,
            COL_NAME,
            COL_MEMO,
            COL_CURRENCY,
            COL_ORIGINAL_CURRENCY,
            COL_EXCHANGE_RATE,
            COL_ACCOUNT_ID,
            COL_BANK_ID,
            COL_ACCOUNT_TYPE,
            COL_INSTITUTION,
        )
}
