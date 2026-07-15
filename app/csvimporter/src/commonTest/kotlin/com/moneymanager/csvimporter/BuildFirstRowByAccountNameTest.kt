@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class BuildFirstRowByAccountNameTest {
    private val gbp = Currency(id = CurrencyId(1), code = "GBP", name = "British Pound")
    private val time = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun row(
        rowIndex: Long,
        passThrough: CsvPassThrough? = null,
        discoveredMappings: List<DiscoveredAccountMapping> = emptyList(),
    ) = CsvTransferWithAttributes(
        transfer =
            Transfer(
                id = TransferId(0),
                timestamp = time,
                description = "row $rowIndex",
                sourceAccountId = AccountId(1),
                targetAccountId = AccountId(2),
                amount = Money(100, gbp),
            ),
        attributes = emptyList(),
        rowIndex = rowIndex,
        passThrough = passThrough,
        discoveredMappings = discoveredMappings,
    )

    private fun prep(rows: List<CsvTransferWithAttributes>) =
        ImportPreparation(validTransfers = rows, errorRows = emptyList(), newAccounts = emptySet(), existingAccountMatches = emptyMap())

    @Test
    fun passThroughConduitAndMerchant_recordTheirOriginatingRow() {
        // A Curve pass-through row creates the conduit ("Curve") and merchant ("Rila Borovets Ad S")
        // accounts outside the discovered-mapping path; both must still get their originating row so the
        // account audit's "Row" link is valid (not the non-existent row 0).
        val passThrough =
            CsvPassThrough(
                conduitNames = listOf("Curve"),
                merchantName = "Rila Borovets Ad S",
                merchantAccountId = null,
                spendDescriptions = listOf("Rila Borovets Ad S"),
                relationshipTypeId = 3,
            )
        val result = buildFirstRowByAccountName(prep(listOf(row(1), row(5, passThrough = passThrough))), emptyMap())
        assertEquals(5L, result["Curve"])
        assertEquals(5L, result["Rila Borovets Ad S"])
    }

    @Test
    fun earliestRowWins_forARepeatedMerchant() {
        val pt = { CsvPassThrough(listOf("Curve"), "Tesco", null, listOf("Tesco"), 3) }
        val result = buildFirstRowByAccountName(prep(listOf(row(7, passThrough = pt()), row(3, passThrough = pt()))), emptyMap())
        assertEquals(3L, result["Tesco"])
    }
}
