@file:OptIn(ExperimentalUuidApi::class)

package com.moneymanager.database.qif

import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.qif.QifImportRecord
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Bridges QIF imports onto the CSV strategy engine. QIF records have a fixed field set, so they
 * are presented to [com.moneymanager.database.csv.CsvTransferMapper] as rows over a fixed set of
 * "columns" named after the QIF fields. This lets QIF reuse the entire CSV strategy machinery
 * (strategies, field mappings, account mappings, the editor) without duplication.
 *
 * Split transactions expand into one row per split (each split is a separately categorised
 * transfer from the same account); every row derived from a record carries that record's
 * [CsvRow.rowIndex] = `recordIndex`, so all resulting transfers link back to the source record.
 */
object QifCsvAdapter {
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

    /** Fixed columns, in order. */
    val columns: List<CsvColumn> =
        headers.mapIndexed { index, name ->
            CsvColumn(
                id = CsvColumnId(Uuid.fromLongs(0L, index.toLong())),
                columnIndex = index,
                originalName = name,
            )
        }

    /**
     * Converts importable QIF records into CSV-style rows, expanding splits. Unsupported records
     * (investments, unknown sections) are skipped.
     */
    fun toRows(records: List<QifImportRecord>): List<CsvRow> =
        records
            .filter { it.supported }
            .flatMap { record ->
                if (record.splits.isEmpty()) {
                    listOf(
                        rowFor(
                            record = record,
                            amount = record.amount,
                            category = record.category,
                            transferAccount = record.transferAccount,
                            memo = record.memo,
                        ),
                    )
                } else {
                    record.splits.map { split ->
                        rowFor(
                            record = record,
                            amount = split.amount,
                            category = split.category,
                            transferAccount = split.transferAccount,
                            memo = split.memo ?: record.memo,
                        )
                    }
                }
            }

    private fun rowFor(
        record: QifImportRecord,
        amount: String?,
        category: String?,
        transferAccount: String?,
        memo: String?,
    ): CsvRow =
        CsvRow(
            rowIndex = record.recordIndex,
            values =
                listOf(
                    record.date.orEmpty(),
                    amount.orEmpty(),
                    record.payee.orEmpty(),
                    memo.orEmpty(),
                    category.orEmpty(),
                    transferAccount.orEmpty(),
                    record.checkNumber.orEmpty(),
                    record.clearedStatus.orEmpty(),
                    record.accountName.orEmpty(),
                ),
        )
}
