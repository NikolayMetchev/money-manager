package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.csvimporter.CsvTransferMapper
import com.moneymanager.csvimporter.MappingResult
import com.moneymanager.csvimporter.needsSourceAccountOverride
import com.moneymanager.csvimporter.parseOfxAsCsv
import com.moneymanager.csvimporter.selectForCsv
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.documentKind
import com.moneymanager.domain.model.ofx.OfxColumns
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.domain.strategy.libraryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The built-in Barclaycard OFX strategy ([BuiltInCsvStrategies.buildBarclaycardOfxStrategy]) over a Barclaycard download. */
class BarclaycardOfxMapperTest {
    private val now = Clock.System.now()
    private val strategy = BuiltInCsvStrategies.buildBarclaycardOfxStrategy(now)

    private val gbp =
        Currency(id = CurrencyId(1), code = "GBP", name = "British Pound", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val eur =
        Currency(id = CurrencyId(2), code = "EUR", name = "Euro", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val usd =
        Currency(id = CurrencyId(3), code = "USD", name = "US Dollar", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val barclaycard = Account(id = AccountId(1), name = "Barclaycard", openingDate = now)

    private val columns = OfxColumns.headers.mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }

    /** A Barclaycard download as it comes, OFX 1.02 with every tag closed and the non-standard `CARD` element. */
    private val download =
        """
        OFXHEADER:100
        DATA:OFXSGML
        VERSION:102
        SECURITY:NONE
        ENCODING:USASCII
        CHARSET:1252
        COMPRESSION:NONE
        OLDFILEUID:NONE
        NEWFILEUID:NONE

        <OFX>
          <CREDITCARDMSGSRSV1>
            <CCSTMTTRNRS>
              <CCSTMTRS>
                <CURDEF>GBP</CURDEF>
                <CCACCTFROM><ACCTID>1234</ACCTID></CCACCTFROM>
                <BANKTRANLIST>
                  <STMTTRN>
                    <TRNTYPE>DEBIT</TRNTYPE>
                    <DTPOSTED>20190722000000</DTPOSTED>
                    <TRNAMT>-6.99</TRNAMT>
                    <FITID>20190722000000001</FITID>
                    <NAME>STREAMING SERVICE</NAME>
                    <CARD>1234</CARD>
                    <MEMO>1234</MEMO>
                  </STMTTRN>
                  <STMTTRN>
                    <TRNTYPE>DEBIT</TRNTYPE>
                    <DTPOSTED>20190723000000</DTPOSTED>
                    <TRNAMT>-7.21</TRNAMT>
                    <FITID>20190723000000001</FITID>
                    <NAME>CAFE PARIS</NAME>
                    <CARD>1234</CARD>
                    <MEMO>1234/8.10 EURO FRANCE</MEMO>
                  </STMTTRN>
                  <STMTTRN>
                    <TRNTYPE>DEBIT</TRNTYPE>
                    <DTPOSTED>20190724000000</DTPOSTED>
                    <TRNAMT>-21.24</TRNAMT>
                    <FITID>20190724000000001</FITID>
                    <NAME>STUDIO, London</NAME>
                    <CARD>1234</CARD>
                    <MEMO>1234/25.99 U.S. DOLLAR GREAT BRITAIN Transaction Fee £0.62</MEMO>
                  </STMTTRN>
                  <STMTTRN>
                    <TRNTYPE>CREDIT</TRNTYPE>
                    <DTPOSTED>20190725000000</DTPOSTED>
                    <TRNAMT>120.00</TRNAMT>
                    <FITID>20190725000000001</FITID>
                    <NAME>Payment By Direct Debit</NAME>
                  </STMTTRN>
                </BANKTRANLIST>
              </CCSTMTRS>
            </CCSTMTTRNRS>
          </CREDITCARDMSGSRSV1>
        </OFX>
        """.trimIndent()

    // Windows-1252, as CHARSET says: every character here is one byte (£ is 0xA3).
    private val bytes = ByteArray(download.length) { download[it].code.toByte() }

    private val rows = parseOfxAsCsv(bytes).rows.mapIndexed { index, values -> CsvRow(index + 1L, values) }

    private fun map(row: CsvRow): MappingResult.Success {
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf(barclaycard.name to barclaycard),
                existingCurrencies = listOf(gbp, eur, usd).associateBy { it.id },
                existingCurrenciesByCode = listOf(gbp, eur, usd).associateBy { it.code },
                assetAliases = mapOf("U.S. DOLLAR" to "USD"),
            )
        return assertIs<MappingResult.Success>(mapper.mapRow(row), "mapping failed")
    }

    @Test
    fun aPurchaseGoesFromTheCardToTheMerchantIdentifiedByItsFitid() {
        val r = map(rows[0])
        assertEquals(barclaycard.id, r.transfer.sourceAccountId)
        assertEquals("STREAMING SERVICE", r.newAccounts.single().name)
        assertEquals("STREAMING SERVICE", r.transfer.description)
        assertEquals(gbp, r.transfer.amount.asset)
        assertEquals("20190722000000001", r.attributes.toMap()["barclaycard-fitid"])
        assertNull(r.conversion)
        assertFalse(strategy.needsSourceAccountOverride())
    }

    @Test
    fun aForeignSpendMovesItsOriginalAmountInTheCurrencyItsMemoNames() {
        val r = map(rows[1])
        assertEquals(Money.fromDisplayValue(BigDecimal("8.10"), eur), r.transfer.amount)
        assertEquals(Money.fromDisplayValue(BigDecimal("7.21"), gbp), r.conversion?.settled)
        assertNull(r.feeAmount)
    }

    @Test
    fun aForeignSpendsTransactionFeeIsCarvedOutOfTheSterlingItSettledAt() {
        val r = map(rows[2])
        assertEquals(Money.fromDisplayValue(BigDecimal("25.99"), usd), r.transfer.amount)
        assertEquals(Money.fromDisplayValue(BigDecimal("20.62"), gbp), r.conversion?.settled)
        assertEquals(Money.fromDisplayValue(BigDecimal("0.62"), gbp), r.feeAmount)
    }

    @Test
    fun aPaymentToTheCardComesInFromAnUnidentifiedPayer() {
        val r = map(rows[3])
        assertEquals(barclaycard.id, r.transfer.targetAccountId)
        assertEquals(r.transfer.sourceAccountId, r.unidentifiedCounterpartyAccountId)
    }

    @Test
    fun contentAutoDetectPicksBarclaycardForItsFitidsOnly() {
        val strategies = BuiltInCsvStrategies.builtInCsvStrategies(now, CurrencyId(1))
        val fitid = OfxColumns.headers.indexOf(OfxColumns.COL_FITID)
        val otherBank = rows.map { row -> CsvRow(row.rowIndex, row.values.mapIndexed { i, v -> if (i == fitid) "AT$v" else v }) }

        assertEquals("Barclaycard (OFX)", strategies.selectForCsv("202107.ofx", columns, rows, wholeDocument = true)?.name)
        assertNull(strategies.selectForCsv("statement.ofx", columns, otherBank, wholeDocument = true))
    }

    @Test
    fun theStrategyIsAnOfxStrategy() {
        assertEquals(ImportFileKind.OFX, strategy.documentKind())
        assertEquals(StrategyKind.OFX, strategy.libraryKind())
    }
}
