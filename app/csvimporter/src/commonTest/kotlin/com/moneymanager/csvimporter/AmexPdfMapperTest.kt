package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.PdfTextLine
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The built-in American Express PDF strategy ([BuiltInCsvStrategies.buildAmexPdfStrategy]) over its statements' layouts. */
class AmexPdfMapperTest {
    private val now = Clock.System.now()
    private val strategy = BuiltInCsvStrategies.buildAmexPdfStrategy(now)

    private val gbp =
        Currency(id = CurrencyId(1), code = "GBP", name = "British Pound", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val amex = Account(id = AccountId(1), name = "American Express", openingDate = now)

    private fun lines(vararg texts: String) = texts.mapIndexed { index, text -> PdfTextLine(1, index + 1, text) }

    private fun map(
        vararg texts: String,
        sourceAccountOverride: AccountId? = amex.id,
    ): List<MappingResult.Success> {
        val staged = PdfRowAssembler(checkNotNull(strategy.config.pdfLines)).assemble(lines(*texts))
        val columns = staged.headers.mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf(amex.name to amex),
                existingCurrencies = mapOf(gbp.id to gbp),
                existingCurrenciesByCode = mapOf(gbp.code to gbp),
                sourceAccountOverride = sourceAccountOverride,
            )
        return staged.rows
            .mapIndexed { index, values -> mapper.mapRow(CsvRow(index + 1L, values)) }
            .filterNot { it is MappingResult.Skipped }
            .map { assertIs<MappingResult.Success>(it, "mapping failed: $it") }
    }

    private fun MappingResult.Success.date(): LocalDate = transfer.timestamp.toLocalDateTime(TimeZone.UTC).date

    private val header2013 = "NIKOLAY I METCHEV                           xxxx-xxxxxx-01234            19/01/13"

    @Test
    fun `a purchase goes from the card to the merchant dated in the statement year or the one before`() {
        val rows =
            map(
                header2013,
                "Dec 20 Dec 21 SAINSBURY'S SUPERMARKET LONDON                                                      15.40",
                "Jan 2  Jan 3  AMAZON.CO.UK                                                                            1,005.89",
            )
        assertEquals(listOf(LocalDate(2012, 12, 20), LocalDate(2013, 1, 2)), rows.map { it.date() })
        val purchase = rows.first()
        assertEquals(amex.id, purchase.transfer.sourceAccountId)
        assertEquals("SAINSBURY'S SUPERMARKET LONDON", purchase.transfer.description)
        assertEquals("SAINSBURY'S SUPERMARKET LONDON", purchase.newAccounts.single().name)
        assertEquals(Money.fromDisplayValue(BigDecimal("1005.89"), gbp), rows[1].transfer.amount)
    }

    @Test
    fun `the strategy names the card account itself whatever folder the file came from`() {
        val row =
            map(
                header2013,
                "Jan 2  Jan 3  AMAZON.CO.UK                                                 9.99",
                sourceAccountOverride = null,
            ).single()
        assertEquals(amex.id, row.transfer.sourceAccountId)
        assertFalse(strategy.needsSourceAccountOverride())
    }

    @Test
    fun `the 2008 layout has one date with the month in full`() {
        val row =
            map(
                "NIKOLAY I METCHEV                             xxxx-xxxxxx-05678              15/03/08",
                "February 28         WM MORRISONS STORES - CAMDEN, LONDON NW1                                29.29",
            ).single()
        assertEquals(LocalDate(2008, 2, 28), row.date())
        assertEquals("WM MORRISONS STORES - CAMDEN, LONDON NW1", row.transfer.description)
    }

    @Test
    fun `a payment to the card comes in from an unidentified payer`() {
        val row =
            map(
                header2013,
                "Jan 2  Jan 2  PAYMENT RECEIVED - THANK YOU                                                       2,609.36",
                "CR",
            ).single()
        assertEquals(amex.id, row.transfer.targetAccountId)
        assertEquals(row.transfer.sourceAccountId, row.unidentifiedCounterpartyAccountId)
    }

    @Test
    fun `a foreign refund is a credit marked after its currency with its rate and fee`() {
        val row =
            map(
                header2013,
                "Aug 25 Aug 25 PAYPAL *INDIEGOGO    35314369001                                     695.00              460.07",
                "UNITED STATES DOLLAR                  CR",
                "Exchange Rate 1.5558 + Nonsterling Transaction Fee 13.36",
            ).single()
        assertEquals(amex.id, row.transfer.targetAccountId)
        assertEquals("PAYPAL *INDIEGOGO    35314369001", row.transfer.description)
        assertNull(row.unidentifiedCounterpartyAccountId)
        val attributes = row.attributes.toMap()
        assertEquals("695.00", attributes["amex-foreign-amount"])
        assertEquals("1.5558", attributes["amex-exchange-rate"])
        assertEquals("13.36", attributes["amex-foreign-fee"])
    }

    @Test
    fun `a section total ends the row before it so a credit total does not mark it`() {
        val row =
            map(
                header2013,
                "Sep 18 Sep 18 ODEON BFI IMAX BOOKIT  MANCHESTER                                                    62.10",
                "Total of new transactions for NIKOLAY I METCHEV                                                      82.77",
                "CR",
            ).single()
        assertEquals(amex.id, row.transfer.sourceAccountId)
    }

    @Test
    fun `the same purchase twice on one statement is two rows told apart by position`() {
        val rows =
            map(
                header2013,
                "Jan 5  Jan 6  TFL TRAVEL CHARGE                                                                    2.40",
                "Jan 5  Jan 6  TFL TRAVEL CHARGE                                                                    2.40",
            )
        assertEquals(2, rows.size)
        assertNotEquals(rows[0].attributes.toMap()["amex-statement-line"], rows[1].attributes.toMap()["amex-statement-line"])
        assertEquals("19/01/13 1:2", rows[0].attributes.toMap()["amex-statement-line"])
    }
}
