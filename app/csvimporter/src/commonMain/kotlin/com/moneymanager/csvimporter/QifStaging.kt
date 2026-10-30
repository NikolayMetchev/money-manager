package com.moneymanager.csvimporter

import com.moneymanager.csv.CsvParseResult
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.qif.QifColumns
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createCsvImport
import com.moneymanager.qif.QifParser
import com.moneymanager.qif.QifRecord
import kotlin.time.Instant

/** Whether [fileName] is a QIF file, which is staged from its records rather than read as CSV text. */
fun isQifFileName(fileName: String): Boolean = fileName.substringAfterLast('.', "").equals("qif", ignoreCase = true)

/**
 * The QIF text [content] as the CSV rows it is staged as, over [QifColumns.headers]: one row per transaction, or
 * one per split of a split transaction (a split's own memo, else the transaction's). Records that aren't
 * transactions (investments, lists) are left out.
 */
fun parseQifAsCsv(content: String): CsvParseResult =
    CsvParseResult(
        headers = QifColumns.headers,
        rows =
            QifParser()
                .parse(content)
                .records
                .filter { it.supported }
                .flatMap { record ->
                    val fields = record.fields
                    if (fields.splits.isEmpty()) {
                        listOf(record.row(fields.amount, fields.category, fields.transferAccount, fields.memo))
                    } else {
                        fields.splits.map { split ->
                            record.row(split.amount, split.category, split.transferAccount, split.memo ?: fields.memo)
                        }
                    }
                },
    )

private fun QifRecord.row(
    amount: String?,
    category: String?,
    transferAccount: String?,
    memo: String?,
): List<String> =
    listOf(
        fields.date.orEmpty(),
        amount.orEmpty(),
        fields.payee.orEmpty(),
        memo.orEmpty(),
        category.orEmpty(),
        transferAccount.orEmpty(),
        fields.checkNumber.orEmpty(),
        fields.clearedStatus.orEmpty(),
        accountName.orEmpty(),
    )

/**
 * Stages the QIF text [content] as a CSV import of its rows ([parseQifAsCsv]): the one way a QIF file is staged,
 * from an import folder or a picked file. From then on it is a CSV import like any other.
 */
suspend fun ImportEngine.stageQifImport(
    fileName: String,
    content: String,
    fileChecksum: String,
    fileLastModified: Instant,
): CsvImportId {
    val parsed = parseQifAsCsv(content)
    return createCsvImport(
        fileName = fileName,
        headers = parsed.headers,
        rows = parsed.rows,
        fileChecksum = fileChecksum,
        fileLastModified = fileLastModified,
    )
}
