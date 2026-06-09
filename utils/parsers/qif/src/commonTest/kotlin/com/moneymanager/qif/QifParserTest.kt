package com.moneymanager.qif

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QifParserTest {
    private val parser = QifParser()

    @Test
    fun parse_blankContent_returnsNoSections() {
        val result = parser.parse("")

        assertEquals(emptyList(), result.sections)
        assertEquals(0, result.unsupportedRecordCount)
    }

    @Test
    fun parse_basicBankTransaction_parsesFields() {
        val qif =
            """
            !Type:Bank
            D03/03/2022
            T-379.00
            PCITY OF SPRINGFIELD
            MMonthly water bill
            LUtilities:Water
            N1234
            CX
            ^
            """.trimIndent()

        val result = parser.parse(qif)

        assertEquals(1, result.sections.size)
        val section = result.sections.single()
        assertEquals(QifSectionType.BANK, section.type)
        val record = section.records.single()
        assertTrue(record.supported)
        assertEquals(0, record.recordIndex)
        assertEquals("03/03/2022", record.fields.date)
        assertEquals("-379.00", record.fields.amount)
        assertEquals("CITY OF SPRINGFIELD", record.fields.payee)
        assertEquals("Monthly water bill", record.fields.memo)
        assertEquals("Utilities:Water", record.fields.category)
        assertNull(record.fields.transferAccount)
        assertEquals("1234", record.fields.checkNumber)
        assertEquals("X", record.fields.clearedStatus)
    }

    @Test
    fun parse_transferAccountInBrackets_populatesTransferAccount() {
        val qif =
            """
            !Type:Bank
            D01/01/2022
            T-100.00
            L[Savings]
            ^
            """.trimIndent()

        val record = parser.parse(qif).records.single()

        assertEquals("Savings", record.fields.transferAccount)
        assertNull(record.fields.category)
    }

    @Test
    fun parse_transferAccountWithClass_stripsBracketsAndKeepsAccount() {
        val qif =
            """
            !Type:Bank
            T-100.00
            L[Savings]/Vacation
            ^
            """.trimIndent()

        val record = parser.parse(qif).records.single()

        assertEquals("Savings", record.fields.transferAccount)
    }

    @Test
    fun parse_uAmountUsedWhenTAbsent() {
        val qif =
            """
            !Type:Bank
            U-42.50
            ^
            """.trimIndent()

        assertEquals(
            "-42.50",
            parser
                .parse(qif)
                .records
                .single()
                .fields.amount,
        )
    }

    @Test
    fun parse_tAmountTakesPrecedenceOverU() {
        val qif =
            """
            !Type:Bank
            U-42.50
            T-43.00
            ^
            """.trimIndent()

        assertEquals(
            "-43.00",
            parser
                .parse(qif)
                .records
                .single()
                .fields.amount,
        )
    }

    @Test
    fun parse_splitTransaction_parsesSplits() {
        val qif =
            """
            !Type:Bank
            D02/02/2022
            T-90.00
            PSupermarket
            SGroceries
            EFood
            $-60.00
            SHousehold
            $-30.00
            ^
            """.trimIndent()

        val record = parser.parse(qif).records.single()

        assertEquals(2, record.fields.splits.size)
        assertEquals(QifSplit(category = "Groceries", memo = "Food", amount = "-60.00"), record.fields.splits[0])
        assertEquals(QifSplit(category = "Household", amount = "-30.00"), record.fields.splits[1])
    }

    @Test
    fun parse_splitWithTransferAccount_populatesSplitTransferAccount() {
        val qif =
            """
            !Type:Bank
            T-50.00
            S[Cash]
            $-50.00
            ^
            """.trimIndent()

        val split =
            parser
                .parse(qif)
                .records
                .single()
                .fields.splits
                .single()

        assertEquals("Cash", split.transferAccount)
        assertNull(split.category)
    }

    @Test
    fun parse_multipleRecords_assignSequentialIndexes() {
        val qif =
            """
            !Type:Bank
            T-1.00
            ^
            T-2.00
            ^
            T-3.00
            ^
            """.trimIndent()

        val records = parser.parse(qif).records

        assertEquals(listOf(0, 1, 2), records.map { it.recordIndex })
        assertEquals(listOf("-1.00", "-2.00", "-3.00"), records.map { it.fields.amount })
    }

    @Test
    fun parse_accountBlockPrecedingTransactions_assignsAccountName() {
        val qif =
            """
            !Account
            NMy Checking
            TBank
            ^
            !Type:Bank
            T-10.00
            ^
            """.trimIndent()

        val result = parser.parse(qif)

        val bankSection = result.sections.single { it.type == QifSectionType.BANK }
        assertEquals("My Checking", bankSection.accountName)
        assertEquals("My Checking", bankSection.records.single().accountName)
    }

    @Test
    fun parse_multipleAccountsWithTransactions_assignsCorrectAccountNames() {
        val qif =
            """
            !Account
            NChecking
            TBank
            ^
            !Type:Bank
            T-10.00
            ^
            !Account
            NCredit Card
            TCCard
            ^
            !Type:CCard
            T-20.00
            ^
            """.trimIndent()

        val result = parser.parse(qif)

        val bank = result.sections.single { it.type == QifSectionType.BANK }
        val ccard = result.sections.single { it.type == QifSectionType.CCARD }
        assertEquals("Checking", bank.accountName)
        assertEquals("Credit Card", ccard.accountName)
    }

    @Test
    fun parse_investmentSection_isParsedButUnsupported() {
        val qif =
            """
            !Type:Invst
            D01/01/2022
            NBuy
            YACME Corp
            T1000.00
            ^
            """.trimIndent()

        val result = parser.parse(qif)

        val record =
            result.sections
                .single()
                .records
                .single()
        assertEquals(QifSectionType.INVESTMENT, record.sectionType)
        assertFalse(record.supported)
        assertEquals("Buy", record.fields.investmentAction)
        assertNull(record.fields.checkNumber)
        assertEquals(1, result.unsupportedRecordCount)
    }

    @Test
    fun parse_categoryListSection_isUnsupported() {
        val qif =
            """
            !Type:Cat
            NUtilities
            DUtility bills
            E
            ^
            """.trimIndent()

        val result = parser.parse(qif)

        assertEquals(QifSectionType.CATEGORY_LIST, result.sections.single().type)
        assertFalse(
            result.sections
                .single()
                .records
                .single()
                .supported,
        )
        assertEquals(1, result.unsupportedRecordCount)
    }

    @Test
    fun parse_unknownFieldCodes_arePreserved() {
        val qif =
            """
            !Type:Bank
            T-5.00
            ZCustomValue
            ^
            """.trimIndent()

        val record = parser.parse(qif).records.single()

        assertEquals(listOf('Z' to "CustomValue"), record.fields.unknownFields)
    }

    @Test
    fun parse_multipleAddressLines_areCollected() {
        val qif =
            """
            !Type:Bank
            T-5.00
            A123 Main St
            ASpringfield
            ^
            """.trimIndent()

        assertEquals(
            listOf("123 Main St", "Springfield"),
            parser
                .parse(qif)
                .records
                .single()
                .fields.address,
        )
    }

    @Test
    fun parse_crlfLineEndings_areHandled() {
        val qif = "!Type:Bank\r\nT-9.99\r\nPStore\r\n^\r\n"

        val record = parser.parse(qif).records.single()

        assertEquals("-9.99", record.fields.amount)
        assertEquals("Store", record.fields.payee)
    }

    @Test
    fun parse_blankLinesBetweenRecords_areIgnored() {
        val qif = "!Type:Bank\n\nT-1.00\n\n^\n\nT-2.00\n^\n"

        val records = parser.parse(qif).records

        assertEquals(2, records.size)
        assertEquals(listOf("-1.00", "-2.00"), records.map { it.fields.amount })
    }

    @Test
    fun parse_rawLinesIncludeTerminator() {
        val qif = "!Type:Bank\nT-1.00\nPStore\n^\n"

        val record = parser.parse(qif).records.single()

        assertEquals(listOf("T-1.00", "PStore", "^"), record.rawLines)
    }

    @Test
    fun parse_optionHeaderAndUnterminatedFinalRecord_areHandled() {
        val qif = "!Option:AutoSwitch\n!Type:Bank\nT-1.00\n"

        val result = parser.parse(qif)

        // The !Option header maps to UNKNOWN; the final record has no trailing ^ but still flushes.
        val bank = result.sections.single { it.type == QifSectionType.BANK }
        assertEquals(
            "-1.00",
            bank.records
                .single()
                .fields.amount,
        )
    }

    @Test
    fun fromHeader_mapsKnownAndUnknownHeaders() {
        assertEquals(QifSectionType.BANK, QifSectionType.fromHeader("!Type:Bank"))
        assertEquals(QifSectionType.OTH_A, QifSectionType.fromHeader("!Type:Oth A"))
        assertEquals(QifSectionType.ACCOUNT_LIST, QifSectionType.fromHeader("!Account"))
        assertEquals(QifSectionType.UNKNOWN, QifSectionType.fromHeader("!Option:AutoSwitch"))
    }
}
