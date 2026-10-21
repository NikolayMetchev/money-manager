package com.moneymanager.csvimporter

import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.model.qif.QifRecordSplit
import com.moneymanager.qif.QifParser
import kotlin.test.Test
import kotlin.test.assertEquals

class QifStagingConversionTest {
    private fun parse(vararg lines: String) = QifParser().parse(lines.joinToString("\n"))

    @Test
    fun bankRecordsKeepEveryFieldAndSplit() {
        val parsed =
            parse(
                "!Type:Bank",
                "D01/02/2026",
                "T-12.50",
                "PTesco",
                "MWeekly shop",
                "LFood",
                "N101",
                "CX",
                "SFood:Groceries",
                "EVeg",
                "$-10.00",
                "S[Savings]",
                "$-2.50",
                "^",
            )

        assertEquals(
            listOf(
                QifImportRecord(
                    recordIndex = 0,
                    sectionType = "BANK",
                    accountName = null,
                    supported = true,
                    rawText =
                        listOf("D01/02/2026", "T-12.50", "PTesco", "MWeekly shop", "LFood", "N101", "CX")
                            .plus(listOf("SFood:Groceries", "EVeg", "$-10.00", "S[Savings]", "$-2.50", "^"))
                            .joinToString("\n"),
                    date = "01/02/2026",
                    amount = "-12.50",
                    payee = "Tesco",
                    memo = "Weekly shop",
                    category = "Food",
                    checkNumber = "101",
                    clearedStatus = "X",
                    splits =
                        listOf(
                            QifRecordSplit(category = "Food:Groceries", memo = "Veg", amount = "-10.00"),
                            QifRecordSplit(transferAccount = "Savings", amount = "-2.50"),
                        ),
                ),
            ),
            parsed.toStagingRecords(),
        )
    }

    @Test
    fun theFirstBankingSectionDecidesTheAccountType() {
        assertEquals(
            "CCARD",
            parse("!Type:Cat", "NFood", "^", "!Type:CCard", "T-1", "^", "!Type:Bank", "T1", "^").dominantAccountTypeOrUnknown(),
        )
        assertEquals("INVESTMENT", parse("!Type:Invst", "NBuy", "^").dominantAccountTypeOrUnknown())
        assertEquals("UNKNOWN", parse().dominantAccountTypeOrUnknown())
    }

    @Test
    fun unsupportedSectionsAreStagedButFlagged() {
        val record = parse("!Type:Invst", "D01/02/2026", "NBuy", "^").toStagingRecords().single()

        assertEquals("INVESTMENT", record.sectionType)
        assertEquals(false, record.supported)
    }
}
