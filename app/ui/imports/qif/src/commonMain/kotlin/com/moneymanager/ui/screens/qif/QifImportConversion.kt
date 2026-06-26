package com.moneymanager.ui.screens.qif

import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.model.qif.QifRecordSplit
import com.moneymanager.qif.QifParseResult

/** Converts a parsed QIF file into the domain records persisted by the repository. */
fun QifParseResult.toImportRecords(): List<QifImportRecord> =
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

/** The dominant account/section type used for strategy matching (first banking section, else first section). */
fun QifParseResult.dominantAccountType(): String =
    sections.firstOrNull { it.type.isBankingTransaction }?.type?.name
        ?: sections.firstOrNull()?.type?.name
        ?: "UNKNOWN"
