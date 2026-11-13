package com.moneymanager.csvimporter

import com.moneymanager.csv.CsvParseResult
import com.moneymanager.domain.model.ofx.OfxColumns
import com.moneymanager.ofx.OfxDateTime
import com.moneymanager.ofx.OfxParser
import com.moneymanager.ofx.OfxStatement
import com.moneymanager.ofx.OfxTransaction

/**
 * The OFX file [bytes] as the CSV rows it is staged as, over [OfxColumns.headers]: one row per transaction of every
 * bank and credit-card statement in the file, in file order. The file's declared character set is honoured.
 */
fun parseOfxAsCsv(bytes: ByteArray): CsvParseResult {
    val document = OfxParser().parse(bytes)
    return CsvParseResult(
        headers = OfxColumns.headers,
        rows =
            document.statements.flatMap { statement ->
                statement.transactions.map { it.row(statement, document.institution) }
            },
    )
}

private fun OfxTransaction.row(
    statement: OfxStatement,
    institution: String?,
): List<String> {
    val posted = datePosted?.let(OfxDateTime::parse)
    return listOf(
        posted?.date ?: datePosted.orEmpty(),
        posted?.time.orEmpty(),
        posted?.utcOffset.orEmpty(),
        dateUser?.let { OfxDateTime.parse(it)?.date ?: it }.orEmpty(),
        amount.orEmpty(),
        type.orEmpty(),
        fitId.orEmpty(),
        refNum.orEmpty(),
        checkNum.orEmpty(),
        name.orEmpty(),
        memo.orEmpty(),
        (currency ?: statement.currency).orEmpty(),
        originalCurrency.orEmpty(),
        currencyRate.orEmpty(),
        statement.accountId.orEmpty(),
        statement.bankId.orEmpty(),
        statement.accountType.orEmpty(),
        institution.orEmpty(),
    )
}
