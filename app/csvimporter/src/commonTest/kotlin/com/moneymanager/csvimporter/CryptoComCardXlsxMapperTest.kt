@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.model.passthrough.PassThroughRule
import com.moneymanager.importengineapi.PassThroughDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Exercises the built-in "Crypto.com Card (Excel)" strategy ([BuiltInCsvStrategies]) — pure config —
 * against row shapes from the real "Card Transaction History" workbook. Unlike the CSV export, its
 * `Amount Processed` column is already signed (negative = spend, positive = load/refund), so direction
 * comes entirely from the standard positive-amount source/target flip plus a Service Abbreviation
 * check that routes card loads to Cash instead of a merchant lookup.
 */
class CryptoComCardXlsxMapperTest {
    private val now = Clock.System.now()
    private val strategy = BuiltInCsvStrategies.buildCryptoComCardXlsxStrategy(now)

    private val gbp = Currency(id = CurrencyId(1), code = "GBP", name = "British Pound")
    private val card = Account(id = AccountId(1), name = "Crypto.com Card", openingDate = now)
    private val cash = Account(id = AccountId(2), name = "Crypto.com Cash", openingDate = now)

    private val columns =
        listOf(
            "Transaction Date",
            "Transaction Time",
            "Service Abbreviation",
            "Card Acceptor Name",
            "Description",
            "Merchant Category Code",
            "Amount Processed",
            "Available Balance",
            "Currency ",
            "Amount Requested",
        ).mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }

    private val curve =
        PassThroughAccount(
            id = PassThroughAccountId(1),
            name = "Curve",
            conduitAccountName = "Curve",
            rules =
                listOf(
                    PassThroughRule(
                        detectionPattern = "(?i)^(?:Refund: )?CRV\\*",
                        merchantPattern = "(?i)^(?:Refund: )?CRV\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                    ),
                ),
        )

    private val mapper =
        CsvTransferMapper(
            strategy = strategy,
            columns = columns,
            existingAccounts = mapOf(card.name to card, cash.name to cash),
            existingCurrencies = mapOf(gbp.id to gbp),
            existingCurrenciesByCode = mapOf(gbp.code to gbp),
            existingCryptoByCode = emptyMap(),
            passThroughDetector = PassThroughDetector(listOf(curve)),
        )

    @Suppress("LongParameterList")
    private fun row(
        date: String,
        time: String,
        serviceAbbreviation: String,
        cardAcceptorName: String,
        description: String,
        amountProcessed: String,
        currency: String,
    ): CsvRow =
        CsvRow(
            rowIndex = 1,
            values =
                listOf(
                    date,
                    time,
                    serviceAbbreviation,
                    cardAcceptorName,
                    description,
                    "5411.0",
                    amountProcessed,
                    "0.0",
                    currency,
                    amountProcessed,
                ),
        )

    private fun map(row: CsvRow): MappingResult.Success {
        val result = mapper.mapRow(row)
        if (result is MappingResult.Error) throw AssertionError("mapping failed: ${result.errorMessage}")
        return assertIs<MappingResult.Success>(result, "mapping failed")
    }

    @Test
    fun `identification columns match the real workbook headers, including the trailing space`() {
        assertEquals(columns.map { it.originalName }.toSet(), strategy.identificationColumns)
    }

    @Test
    fun `a negative purchase debits the card and credits a merchant account`() {
        val r =
            map(
                row(
                    date = "11/27/2021",
                    time = "09:12:00",
                    serviceAbbreviation = "POS Signature Purchase",
                    cardAcceptorName = "Spotify UK               Stockholm      SWE",
                    description = "POS Signature Purchase",
                    amountProcessed = "-4.5",
                    currency = "GBP",
                ),
            )

        assertEquals(card.id, r.transfer.sourceAccountId)
        assertEquals(Money.fromDisplayValue(BigDecimal("4.5"), gbp), r.transfer.amount)
        // The fixed-width padding + city/country ("               Stockholm      SWE") must be trimmed
        // off, both for a clean account name and because pass-through detection depends on it.
        assertEquals(listOf(NewAccount("Spotify UK", Category.UNCATEGORIZED_ID)), r.newAccounts)
        assertNull(r.passThrough)
    }

    @Test
    fun `a CRV_ card acceptor name is routed through the Curve pass-through, not a raw junk account`() {
        val r =
            map(
                row(
                    date = "11/27/2021",
                    time = "10:00:00",
                    serviceAbbreviation = "POS Signature Purchase",
                    cardAcceptorName = "CRV*Card verification    London         GBR",
                    description = "POS Signature Purchase",
                    amountProcessed = "-1.5",
                    currency = "GBP",
                ),
            )

        val passThrough = r.passThrough
        checkNotNull(passThrough) { "expected the CRV* card acceptor name to match the Curve pass-through" }
        assertEquals(listOf("Curve"), passThrough.conduitNames)
        assertEquals("Card verification", passThrough.merchantName)
        // The conduit becomes the target, not a raw "CRV*Card verification    London         GBR" account.
        assertEquals(card.id, r.transfer.sourceAccountId)
        assertEquals(setOf("Curve", "Card verification"), r.newAccounts.map { it.name }.toSet())
    }

    @Test
    fun `a blank card acceptor name routes to Cash instead of failing to resolve an empty account`() {
        val r =
            map(
                row(
                    date = "12/03/2021",
                    time = "06:20:49",
                    serviceAbbreviation = "Batch Credit Funds Transfer",
                    cardAcceptorName = "",
                    description = "Batch Credit Funds Transfer",
                    amountProcessed = "245.86",
                    currency = "GBP",
                ),
            )

        // Positive amount flips: Cash (this branch's mapped account) becomes the source, Card the target.
        assertEquals(cash.id, r.transfer.sourceAccountId)
        assertEquals(card.id, r.transfer.targetAccountId)
        assertEquals(emptyList(), r.newAccounts)
    }

    @Test
    fun `a positive card load credits the card from Cash, not a merchant lookup`() {
        val r =
            map(
                row(
                    date = "11/26/2021",
                    time = "14:34:00",
                    serviceAbbreviation = "LdExtDbCr",
                    cardAcceptorName = "                                        GBR",
                    description = "GBP/200.0-Card Load",
                    amountProcessed = "200.0",
                    currency = "GBP",
                ),
            )

        // Positive amount flips source/target: Cash (this branch's mapped account) becomes the source.
        assertEquals(cash.id, r.transfer.sourceAccountId)
        assertEquals(card.id, r.transfer.targetAccountId)
        assertEquals(Money.fromDisplayValue(BigDecimal("200.0"), gbp), r.transfer.amount)
        assertEquals(emptyList(), r.newAccounts)
    }

    @Test
    fun `timestamp combines the date and time columns`() {
        val r =
            map(
                row(
                    date = "11/26/2021",
                    time = "14:21:29",
                    serviceAbbreviation = "POS Signature Purchase",
                    cardAcceptorName = "CRV*Card verification    London         GBR",
                    description = "Account Verification Transaction",
                    amountProcessed = "0.0",
                    currency = "GBP",
                ),
            )

        assertEquals("2021-11-26T14:21:29Z", r.transfer.timestamp.toString())
    }
}
