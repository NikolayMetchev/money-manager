package com.moneymanager.csvimporter

import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.model.qif.QifRecordSplit
import com.moneymanager.qif.QifParseResult

// Pure QIF parse-result → domain-record conversion used by the directory scanner when staging .qif
// files. Mirrors the same conversion in the QIF import UI; kept here so the scanner stays DB-free.

internal fun QifParseResult.toStagingRecords(): List<QifImportRecord> =
    records.map { record ->
        QifImportRecord(
            recordIndex = record.recordIndex.toLong(),
            sectionType = record.sectionType.name,
            accountName = record.accountName,
            supported = record.supported,
            rawText = record.rawLines.joinToString("\n"),
            date = record.fields.date,
            amount = record.fields.amount,
            payee = record.fields.payee,
            memo = record.fields.memo,
            category = record.fields.category,
            transferAccount = record.fields.transferAccount,
            checkNumber = record.fields.checkNumber,
            clearedStatus = record.fields.clearedStatus,
            splits =
                record.fields.splits.map { split ->
                    QifRecordSplit(
                        category = split.category,
                        transferAccount = split.transferAccount,
                        memo = split.memo,
                        amount = split.amount,
                    )
                },
        )
    }

/** The dominant account/section type used for strategy matching (first banking section, else first). */
internal fun QifParseResult.dominantAccountTypeOrUnknown(): String =
    sections.firstOrNull { it.type.isBankingTransaction }?.type?.name
        ?: sections.firstOrNull()?.type?.name
        ?: "UNKNOWN"
