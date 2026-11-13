package com.moneymanager.ofx

/** An OFX file's bank and credit-card statements, in document order. Values stay the file's raw text. */
data class OfxDocument(
    /** The institution's `<FI><ORG>`, when the sign-on names one. */
    val institution: String?,
    val statements: List<OfxStatement>,
)

/** One `<STMTRS>` (bank account) or `<CCSTMTRS>` (credit card) statement. */
data class OfxStatement(
    /** `<CURDEF>`: the currency every amount is in unless a transaction names another. */
    val currency: String?,
    val accountId: String?,
    /** `<BANKID>` (a bank account's sort code or routing number); credit cards have none. */
    val bankId: String?,
    /** `<ACCTTYPE>` (CHECKING, SAVINGS, …); `CREDITCARD` for a credit-card statement. */
    val accountType: String?,
    val transactions: List<OfxTransaction>,
)

/** One `<STMTTRN>`: OFX's standard transaction fields. Elements outside the standard are ignored. */
data class OfxTransaction(
    val type: String?,
    val datePosted: String?,
    val dateUser: String?,
    val amount: String?,
    val fitId: String?,
    val refNum: String?,
    val checkNum: String?,
    /** `<NAME>`, else the `<PAYEE>` aggregate's `<NAME>`. */
    val name: String?,
    val memo: String?,
    /** `<CURRENCY><CURSYM>`: the transaction's own currency, when it isn't the statement's. */
    val currency: String?,
    /** `<ORIGCURRENCY><CURSYM>`: the currency the transaction was originally made in. */
    val originalCurrency: String?,
    /** `<CURRATE>` of whichever of `<CURRENCY>`/`<ORIGCURRENCY>` the transaction has. */
    val currencyRate: String?,
)

/**
 * Reads OFX files, version 1.x (SGML) and 2.x (XML) alike, into their bank and credit-card statements.
 * Investment statements and other message sets are left out.
 */
class OfxParser {
    fun parse(bytes: ByteArray): OfxDocument = parse(decodeOfxBytes(bytes))

    fun parse(content: String): OfxDocument {
        val root = OfxMarkup.parse(content)
        val statements =
            root.findAll("STMTRS").map { it.toStatement("BANKACCTFROM", accountType = null) } +
                root.findAll("CCSTMTRS").map { it.toStatement("CCACCTFROM", accountType = CREDIT_CARD) }
        return OfxDocument(
            institution = root.findAll("FI").firstNotNullOfOrNull { it.valueAt("ORG") },
            statements = statements,
        )
    }

    private fun OfxElement.toStatement(
        accountAggregate: String,
        accountType: String?,
    ): OfxStatement {
        val account = child(accountAggregate)
        return OfxStatement(
            currency = valueAt("CURDEF"),
            accountId = account?.valueAt("ACCTID"),
            bankId = account?.valueAt("BANKID"),
            accountType = account?.valueAt("ACCTTYPE") ?: accountType,
            transactions =
                child("BANKTRANLIST")
                    ?.children
                    .orEmpty()
                    .filter { it.name == "STMTTRN" }
                    .map { it.toTransaction() },
        )
    }

    private fun OfxElement.toTransaction(): OfxTransaction {
        val currency = child("CURRENCY")
        val originalCurrency = child("ORIGCURRENCY")
        return OfxTransaction(
            type = valueAt("TRNTYPE"),
            datePosted = valueAt("DTPOSTED"),
            dateUser = valueAt("DTUSER"),
            amount = valueAt("TRNAMT"),
            fitId = valueAt("FITID"),
            refNum = valueAt("REFNUM"),
            checkNum = valueAt("CHECKNUM"),
            name = valueAt("NAME") ?: valueAt("PAYEE", "NAME"),
            memo = valueAt("MEMO"),
            currency = currency?.valueAt("CURSYM"),
            originalCurrency = originalCurrency?.valueAt("CURSYM"),
            currencyRate = (currency ?: originalCurrency)?.valueAt("CURRATE"),
        )
    }

    private companion object {
        const val CREDIT_CARD = "CREDITCARD"
    }
}
