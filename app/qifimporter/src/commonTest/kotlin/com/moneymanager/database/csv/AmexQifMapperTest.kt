package com.moneymanager.database.csv

import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.csvimporter.CsvTransferMapper
import com.moneymanager.csvimporter.MappingResult
import com.moneymanager.csvimporter.needsSourceAccountOverride
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.qifimporter.QifCsvAdapter
import com.moneymanager.qifimporter.qifCompatible
import com.moneymanager.qifimporter.selectForQifContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock

/** The built-in American Express QIF strategy ([BuiltInCsvStrategies.buildAmexQifStrategy]) over Amex's record shapes. */
class AmexQifMapperTest {
    private val now = Clock.System.now()
    private val strategy = BuiltInCsvStrategies.buildAmexQifStrategy(now)

    private val gbp =
        Currency(id = CurrencyId(1), code = "GBP", name = "British Pound", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val amex = Account(id = AccountId(1), name = "American Express", openingDate = now)

    /** A QIF-shaped row (columns in [QifCsvAdapter] order), as Amex writes it: `M` and `N` always set. */
    private fun row(
        payee: String,
        amount: String,
        memo: String,
        reference: String = "10550530",
    ): CsvRow = CsvRow(rowIndex = 1, values = listOf("19/03/2020", amount, payee, memo, "", "", reference, "", ""))

    private fun map(
        row: CsvRow,
        sourceAccountOverride: AccountId? = amex.id,
    ): MappingResult.Success {
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = QifCsvAdapter.columns,
                existingAccounts = mapOf(amex.name to amex),
                existingCurrencies = mapOf(gbp.id to gbp),
                existingCurrenciesByCode = mapOf(gbp.code to gbp),
                sourceAccountOverride = sourceAccountOverride,
            )
        val result = mapper.mapRow(row)
        return assertIs<MappingResult.Success>(result, "mapping failed: $result")
    }

    @Test
    fun `a purchase goes from the card to the merchant and is tagged with its reference and dates`() {
        val r = map(row("SAINSBURY'S SUPERMARKET LONDON", "-16.10", " GOODS Process Date 19/03/2020", reference = "10698290"))
        assertEquals(amex.id, r.transfer.sourceAccountId)
        assertEquals("SAINSBURY'S SUPERMARKET LONDON", r.newAccounts.single().name)
        assertEquals("SAINSBURY'S SUPERMARKET LONDON", r.transfer.description)
        val attributes = r.attributes.toMap()
        assertEquals("10698290", attributes["amex-reference"])
        assertEquals("19/03/2020", attributes["amex-process-date"])
        assertEquals("GOODS", attributes["amex-merchant-type"])
    }

    @Test
    fun `the strategy names the card account itself whatever folder the file came from`() {
        val r = map(row("AMAZON.CO.UK", "-9.99", " GOODS Process Date 19/03/2020"), sourceAccountOverride = null)
        assertEquals(amex.id, r.transfer.sourceAccountId)
        assertFalse(strategy.needsSourceAccountOverride())
    }

    @Test
    fun `a payment to the card comes in from an unidentified payer`() {
        val r = map(row("PAYMENT RECEIVED - THANK YOU", "1574.62", "  Process Date 02/04/2020"))
        assertEquals(amex.id, r.transfer.targetAccountId)
        assertEquals("PAYMENT RECEIVED - THANK YOU", r.newAccounts.single().name)
        // Amex never says who paid: the payer is a placeholder, which reconciles against the paying bank's record.
        assertEquals(r.transfer.sourceAccountId, r.unidentifiedCounterpartyAccountId)
        assertEquals(null, r.attributes.toMap()["amex-merchant-type"])
    }

    @Test
    fun `a refund names its merchant and is not a placeholder`() {
        val r = map(row("ZIPCAR WIMBLEDON", "45.00", "  Process Date 23/03/2020"))
        assertEquals(amex.id, r.transfer.targetAccountId)
        assertEquals("ZIPCAR WIMBLEDON", r.newAccounts.single().name)
        assertNull(r.unidentifiedCounterpartyAccountId)
    }

    @Test
    fun `content auto-detect picks American Express for amex rows and nothing for another bank`() {
        val strategies = BuiltInCsvStrategies.builtInCsvStrategies(now).qifCompatible()
        val amexRows = listOf(row("ZIPCAR WIMBLEDON", "-45.00", "  Process Date 21/03/2020"))
        val genericRows = listOf(CsvRow(rowIndex = 1, values = listOf("19/03/2020", "-5.00", "Tesco", "", "", "", "", "", "")))

        assertEquals("American Express (QIF)", strategies.selectForQifContent(amexRows, QifCsvAdapter.columns)?.name)
        assertNull(strategies.selectForQifContent(genericRows, QifCsvAdapter.columns))
    }
}
