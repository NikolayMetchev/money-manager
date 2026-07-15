@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.csvimporter

import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.ContentMatchRule
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

class StrategySelectorTest {
    private val columns =
        listOf(
            CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
            CsvColumn(CsvColumnId(Uuid.random()), 1, "Amount"),
            CsvColumn(CsvColumnId(Uuid.random()), 2, "Kind"),
        )
    private val headings = setOf("Date", "Amount", "Kind")

    private fun strategy(
        name: String,
        identificationColumns: Set<String> = headings,
        contentMatchRules: List<ContentMatchRule> = emptyList(),
        fileNamePattern: String? = null,
    ): CsvImportStrategy {
        val now = Clock.System.now()
        return CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = name,
            identificationColumns = identificationColumns,
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(FieldMappingId(Uuid.random()), TransferField.SOURCE_ACCOUNT, AccountId(1)),
                    TransferField.TARGET_ACCOUNT to
                        HardCodedAccountMapping(FieldMappingId(Uuid.random()), TransferField.TARGET_ACCOUNT, AccountId(2)),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMESTAMP,
                            dateColumnName = "Date",
                            dateFormat = "dd/MM/yyyy",
                        ),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = "Amount",
                        ),
                    TransferField.CURRENCY to
                        HardCodedCurrencyMapping(FieldMappingId(Uuid.random()), TransferField.CURRENCY, CurrencyId(1L)),
                ),
            contentMatchRules = contentMatchRules,
            fileNamePattern = fileNamePattern,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun rows(vararg kinds: String): List<CsvRow> =
        kinds.mapIndexed { index, kind -> CsvRow(rowIndex = index + 1L, values = listOf("01/01/2026", "1.00", kind)) }

    private val kindRule = ContentMatchRule("Kind", "^viban_")
    private val blankKindRule = ContentMatchRule("Kind", "^$")

    @Test
    fun `column mismatch returns null even with matching filename`() {
        val s = strategy("A", identificationColumns = setOf("Other"), fileNamePattern = "^fiat_")
        assertNull(listOf(s).selectForCsv("fiat_transactions.csv", columns, rows("viban_deposit")))
    }

    @Test
    fun `a file missing an optional column still matches by filename when nothing matches exactly`() {
        // The strategy knows an extra "Transaction Hash" column a later export added; this older file
        // has one fewer. No strategy matches the columns exactly, so the tolerant fallback + filename win.
        val fiat = strategy("Fiat", identificationColumns = headings + "Transaction Hash", fileNamePattern = "^fiat_")
        val selected = listOf(fiat).selectForCsv("fiat_transactions_record_2021.csv", columns, rows("viban_deposit"))
        assertEquals("Fiat", selected?.name)
    }

    @Test
    fun `an exact column match still wins over a tolerant candidate`() {
        val exact = strategy("Exact", fileNamePattern = "^fiat_")
        val superset = strategy("Superset", identificationColumns = headings + "Extra", fileNamePattern = "^fiat_")
        val selected = listOf(superset, exact).selectForCsv("fiat_x.csv", columns, rows(""))
        assertEquals("Exact", selected?.name)
    }

    @Test
    fun `a file with an unknown extra column is not misrouted by the tolerant fallback`() {
        // The strategy's identification columns are a strict subset of the file's, so the file carries a
        // column the strategy doesn't know — it must not tolerantly match.
        val s = strategy("A", identificationColumns = setOf("Date", "Amount"), fileNamePattern = "^fiat_")
        assertNull(listOf(s).selectForCsv("fiat_transactions.csv", columns, rows("viban_deposit")))
    }

    @Test
    fun `filename match beats higher content score`() {
        val byName = strategy("Card", fileNamePattern = "^card_")
        val byContent = strategy("Fiat", contentMatchRules = listOf(kindRule))
        val selected =
            listOf(byContent, byName).selectForCsv("card_transactions_record_1.csv", columns, rows("viban_deposit", "viban_deposit"))
        assertEquals("Card", selected?.name)
    }

    @Test
    fun `filename matching is case-insensitive and partial`() {
        val s = strategy("Card", fileNamePattern = "card_transactions")
        val selected = listOf(s).selectForCsv("CARD_TRANSACTIONS_RECORD_2026.CSV", columns, rows(""))
        assertEquals("Card", selected?.name)
    }

    @Test
    fun `content ties between filename matches break by score`() {
        val fiat = strategy("Fiat", contentMatchRules = listOf(kindRule), fileNamePattern = "transactions_record")
        val card = strategy("Card", contentMatchRules = listOf(blankKindRule), fileNamePattern = "transactions_record")
        val selected =
            listOf(card, fiat)
                .selectForCsv("fiat_transactions_record_1.csv", columns, rows("viban_deposit", "viban_card_top_up"))
        assertEquals("Fiat", selected?.name)
    }

    @Test
    fun `content match above threshold selects strategy when filename does not match`() {
        val fiat = strategy("Fiat", contentMatchRules = listOf(kindRule), fileNamePattern = "^fiat_")
        val selected = listOf(fiat).selectForCsv("renamed.csv", columns, rows("viban_deposit", "viban_card_top_up", "viban_withdrawal"))
        assertEquals("Fiat", selected?.name)
    }

    @Test
    fun `content match below half the sample is rejected`() {
        // A crypto_ file containing one viban row out of four must not route to the Fiat strategy.
        val fiat = strategy("Fiat", contentMatchRules = listOf(kindRule), fileNamePattern = "^fiat_")
        val selected =
            listOf(fiat).selectForCsv(
                "crypto_transactions_record_1.csv",
                columns,
                rows("referral_card_cashback", "reimbursement", "viban_purchase", "crypto_to_exchange_transfer"),
            )
        assertNull(selected)
    }

    @Test
    fun `blank kind content rule matches empty values`() {
        val card = strategy("Card", contentMatchRules = listOf(blankKindRule))
        val selected = listOf(card).selectForCsv("renamed.csv", columns, rows("", "", ""))
        assertEquals("Card", selected?.name)
    }

    @Test
    fun `signal-less strategy is the fallback when no signals match`() {
        val monzoLike = strategy("Monzo")
        val fiat = strategy("Fiat", contentMatchRules = listOf(kindRule), fileNamePattern = "^fiat_")
        val selected = listOf(fiat, monzoLike).selectForCsv("export.csv", columns, rows("payment", "payment"))
        assertEquals("Monzo", selected?.name)
    }

    @Test
    fun `null when only signal-bearing candidates exist and none match`() {
        val fiat = strategy("Fiat", contentMatchRules = listOf(kindRule), fileNamePattern = "^fiat_")
        val card = strategy("Card", contentMatchRules = listOf(blankKindRule), fileNamePattern = "^card_")
        assertNull(listOf(fiat, card).selectForCsv("crypto_x.csv", columns, rows("referral_card_cashback")))
    }

    @Test
    fun `empty rows fall through to fallback tier`() {
        val fallback = strategy("Fallback")
        val fiat = strategy("Fiat", contentMatchRules = listOf(kindRule))
        val selected = listOf(fiat, fallback).selectForCsv("empty.csv", columns, emptyList())
        assertEquals("Fallback", selected?.name)
    }

    @Test
    fun `malformed filename regex is ignored rather than failing selection`() {
        val broken = strategy("Broken", fileNamePattern = "([")
        val fallback = strategy("Fallback")
        val selected = listOf(broken, fallback).selectForCsv("anything.csv", columns, rows(""))
        assertEquals("Fallback", selected?.name)
    }

    @Test
    fun `fallback ties break deterministically by name`() {
        val b = strategy("B")
        val a = strategy("A")
        val selected = listOf(b, a).selectForCsv("x.csv", columns, rows(""))
        assertEquals("A", selected?.name)
    }

    @Test
    fun `a Curve export selects the Curve CSV strategy`() {
        val builtIns = BuiltInCsvStrategies.builtInCsvStrategies(Clock.System.now())
        val curveColumns =
            listOf(
                CsvColumn(CsvColumnId(Uuid.random()), 0, ""),
                CsvColumn(CsvColumnId(Uuid.random()), 1, "Created Date"),
                CsvColumn(CsvColumnId(Uuid.random()), 2, "Merchant Name"),
                CsvColumn(CsvColumnId(Uuid.random()), 3, "Funding Card Last 4 Digits"),
                CsvColumn(CsvColumnId(Uuid.random()), 4, "Merchant MCC Code"),
                CsvColumn(CsvColumnId(Uuid.random()), 5, "Txn Currency"),
                CsvColumn(CsvColumnId(Uuid.random()), 6, "Txn Amount"),
            )
        val curveRows =
            listOf(
                CsvRow(rowIndex = 1L, values = listOf("1", "2026-06-16", "TFL", "7721", "4111", "GBP", "1.75")),
                CsvRow(rowIndex = 2L, values = listOf("2", "2026-06-15", "AMAZON", "1142", "5999", "GBP", "62.24")),
            )
        val selected = builtIns.selectForCsv("Transaction History 2026-06-16.csv", curveColumns, curveRows)
        assertEquals("Curve CSV", selected?.name)
    }

    @Test
    fun `a Wise export still selects the Wise CSV strategy alongside Curve`() {
        // Both Curve and Wise name their export "transaction-history"-ish, so this guards against the
        // Curve fileNamePattern hijacking a Wise file — the disjoint column sets must keep them apart.
        val builtIns = BuiltInCsvStrategies.builtInCsvStrategies(Clock.System.now())
        val wiseHeaders =
            listOf(
                "ID",
                "Status",
                "Direction",
                "Created on",
                "Finished on",
                "Source fee amount",
                "Source fee currency",
                "Target fee amount",
                "Target fee currency",
                "Source name",
                "Source amount (after fees)",
                "Source currency",
                "Target name",
                "Target amount (after fees)",
                "Target currency",
                "Exchange rate",
                "Reference",
                "Batch",
                "Created by",
                "Category",
                "Note",
            )
        val wiseColumns = wiseHeaders.mapIndexed { i, name -> CsvColumn(CsvColumnId(Uuid.random()), i, name) }
        val wiseRow =
            CsvRow(
                rowIndex = 1L,
                values =
                    listOf(
                        "TRANSFER-1",
                        "COMPLETED",
                        "OUT",
                        "2026-05-30 08:01:36",
                        "2026-05-30 08:01:50",
                        "0.00",
                        "EUR",
                        "",
                        "",
                        "Nikolay",
                        "150.0",
                        "EUR",
                        "Teodora",
                        "150.0",
                        "EUR",
                        "1.0",
                        "gift",
                        "",
                        "Nikolay",
                        "General",
                        "",
                    ),
            )
        val selected = builtIns.selectForCsv("transaction-history.csv", wiseColumns, listOf(wiseRow))
        assertEquals("Wise CSV", selected?.name)
    }
}
