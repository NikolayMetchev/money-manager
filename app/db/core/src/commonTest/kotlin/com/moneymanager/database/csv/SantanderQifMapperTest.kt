@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.CsvTransferMapper
import com.moneymanager.csvimporter.MappingResult
import com.moneymanager.database.DatabaseConfig
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.qif.QifColumns
import com.moneymanager.qifimporter.QifCsvAdapter
import com.moneymanager.qifimporter.qifCompatible
import com.moneymanager.qifimporter.selectForQifContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Exercises the built-in Santander QIF strategy ([DatabaseConfig.buildSantanderQifStrategy]) — which
 * is pure config — against the real Santander Payee formats. Verifies the config-driven parsing
 * extracts a clean counterparty (target account), tags transaction-type / reference / mandate / date
 * attributes, cleans the description, and flags person-to-person counterparties.
 */
class SantanderQifMapperTest {
    private val now = Clock.System.now()
    private val strategy = DatabaseConfig.buildSantanderQifStrategy(now)

    private val gbp = Currency(id = CurrencyId(1), code = "GBP", name = "British Pound")
    private val santander = Account(id = AccountId(1), name = "Santander", openingDate = now)

    private fun mapper(): CsvTransferMapper =
        CsvTransferMapper(
            strategy = strategy,
            columns = QifCsvAdapter.columns,
            existingAccounts = mapOf(santander.name to santander),
            existingCurrencies = mapOf(gbp.id to gbp),
            existingCurrenciesByCode = mapOf(gbp.code to gbp),
            sourceAccountOverride = santander.id,
        )

    /** Builds a QIF-shaped row (columns in [QifCsvAdapter] order) with the given payee and amount. */
    private fun row(
        payee: String,
        amount: String,
        date: String = "27/03/2021",
    ): CsvRow =
        CsvRow(
            rowIndex = 1,
            values = listOf(date, amount, payee, "", "", "", "", "", ""),
        )

    private fun map(
        payee: String,
        amount: String,
    ): MappingResult.Success {
        val result = mapper().mapRow(row(payee, amount))
        return assertIs<MappingResult.Success>(result, "mapping failed: $result")
    }

    private fun MappingResult.Success.attrs(): Map<String, String> = attributes.toMap()

    @Test
    fun `card payment extracts merchant as target account and tags type`() {
        val r = map("CARD PAYMENT TO VANGUARD,7.32 GBP, RATE 1.00/GBP ON 27-03-2021, 7.32", "-7.32")
        // Outgoing (negative): target is the counterparty.
        assertEquals("VANGUARD", newTargetName(r))
        assertEquals("CARD_PAYMENT", r.attrs()["santander-transaction-type"])
        assertEquals("27-03-2021", r.attrs()["santander-posted-date"])
        assertNull(r.personalCounterpartyName)
    }

    @Test
    fun `direct debit extracts payee, reference and mandate`() {
        val r = map("DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 3746-935125-04002, MANDATE NO 0013, 1352.23", "-1352.23")
        assertEquals("AMERICAN EXPRESS", newTargetName(r))
        assertEquals("DIRECT_DEBIT", r.attrs()["santander-transaction-type"])
        assertEquals("3746-935125-04002", r.attrs()["santander-reference"])
        assertEquals("0013", r.attrs()["santander-mandate"])
        assertNull(r.personalCounterpartyName)
    }

    @Test
    fun `faster payment receipt flags the sender as a person`() {
        val r = map("FASTER PAYMENTS RECEIPT REF.OLGA FROM ZAKHARENKO O, 250.00", "250.00")
        // Incoming (positive) flips accounts, but the counterparty is still detected as a person.
        assertEquals("ZAKHARENKO O", r.personalCounterpartyName)
        assertEquals("FASTER_PAYMENT_IN", r.attrs()["santander-transaction-type"])
    }

    @Test
    fun `faster payment receipt without reference still parses sender`() {
        val r = map("FASTER PAYMENTS RECEIPT  FROM NIKOLAY METCHEV, 1000.00", "1000.00")
        assertEquals("NIKOLAY METCHEV", r.personalCounterpartyName)
    }

    @Test
    fun `bill payment via faster payment flags recipient as a person with reference`() {
        val r = map("BILL PAYMENT VIA FASTER PAYMENT TO JASMINA KRUMOVA REFERENCE CLEANING , MANDATE NO 1, 27.00GBP", "-27.00")
        assertEquals("JASMINA KRUMOVA", newTargetName(r))
        assertEquals("JASMINA KRUMOVA", r.personalCounterpartyName)
        assertEquals("BILL_PAYMENT", r.attrs()["santander-transaction-type"])
        assertEquals("CLEANING", r.attrs()["santander-reference"])
        assertEquals("1", r.attrs()["santander-mandate"])
    }

    @Test
    fun `bank giro credit extracts reference as counterparty (not a person)`() {
        val r = map("BANK GIRO CREDIT REF HMRC CHILD BENEFIT, METCHEVNIKOL850001, 84.60", "84.60")
        assertEquals("HMRC CHILD BENEFIT", newTargetName(r))
        assertEquals("BANK_GIRO_CREDIT", r.attrs()["santander-transaction-type"])
        assertNull(r.personalCounterpartyName)
    }

    @Test
    fun `monthly account fee maps to a fixed counterparty via a non-template rule`() {
        val r = map("MONTHLY ACCOUNT FEE, 5.00GBP", "-5.00")
        assertEquals("Santander Fees", newTargetName(r))
        assertEquals("ACCOUNT_FEE", r.attrs()["santander-transaction-type"])
    }

    @Test
    fun `description is cleaned of the trailing amount`() {
        val r = map("FASTER PAYMENTS RECEIPT REF.OLGA FROM ZAKHARENKO O, 250.00", "250.00")
        assertEquals("FASTER PAYMENTS RECEIPT REF.OLGA FROM ZAKHARENKO O", r.transfer.description)
    }

    @Test
    fun `unmatched payee falls back to the raw payee as the counterparty`() {
        // No leading Santander keyword -> regex rules don't match -> fallback uses the raw payee.
        val r = map("SOME UNKNOWN NARRATIVE", "-10.00")
        assertEquals("SOME UNKNOWN NARRATIVE", newTargetName(r))
        assertNull(r.attrs()["santander-transaction-type"])
        assertNull(r.personalCounterpartyName)
    }

    @Test
    fun `persisted account mapping suppresses person detection`() {
        // A user-persisted mapping remaps the FASTER PAYMENTS counterparty onto an existing account.
        val mapped = Account(id = AccountId(42), name = "My Savings", openingDate = now)
        val mapping =
            CsvAccountMapping(
                id = 1L,
                strategyId = strategy.id,
                columnName = QifColumns.COL_PAYEE,
                valuePattern = Regex("ZAKHARENKO O"),
                accountId = mapped.id,
                createdAt = now,
                updatedAt = now,
            )
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = QifCsvAdapter.columns,
                existingAccounts = mapOf(santander.name to santander, mapped.name to mapped),
                existingCurrencies = mapOf(gbp.id to gbp),
                existingCurrenciesByCode = mapOf(gbp.code to gbp),
                accountMappings = listOf(mapping),
                sourceAccountOverride = santander.id,
            )
        val r =
            assertIs<MappingResult.Success>(
                mapper.mapRow(row("FASTER PAYMENTS RECEIPT REF.OLGA FROM ZAKHARENKO O, 250.00", "250.00")),
            )
        // The persisted mapping overrode the counterparty, so no Person/ownership is created from the
        // regex name, and no new account is discovered (it resolved to the mapped existing account).
        assertNull(r.personalCounterpartyName)
        assertTrue(r.newAccounts.isEmpty())
    }

    @Test
    fun `content auto-detect picks Santander for santander rows and the generic QIF strategy otherwise`() {
        val strategies = DatabaseConfig.builtInCsvStrategies(now).qifCompatible()
        val santanderRows =
            listOf(row("DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF X, MANDATE NO 1, 5.00", "-5.00"))
        val genericRows = listOf(row("Tesco", "-5.00"))

        assertEquals("Santander (QIF)", strategies.selectForQifContent(santanderRows, QifCsvAdapter.columns)?.name)
        assertEquals("QIF", strategies.selectForQifContent(genericRows, QifCsvAdapter.columns)?.name)
    }

    /** The new (counterparty) account discovered on the target side, regardless of flip. */
    private fun newTargetName(r: MappingResult.Success): String {
        assertTrue(r.newAccounts.isNotEmpty(), "expected a discovered counterparty account")
        return r.newAccounts.first().name
    }
}
