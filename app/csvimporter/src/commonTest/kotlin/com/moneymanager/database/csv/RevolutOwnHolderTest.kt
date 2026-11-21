package com.moneymanager.database.csv

import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.csvimporter.CsvTransferMapper
import com.moneymanager.csvimporter.MappingResult
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

class RevolutOwnHolderTest {
    private val strategy = BuiltInCsvStrategies.buildRevolutCsvStrategy(Clock.System.now())
    private val gbp = Currency(id = CurrencyId(1), code = "GBP", name = "British Pound", scaleFactor = 100)
    private val revolut = Account(id = AccountId(13), name = "Revolut", openingDate = Clock.System.now())
    private val holder = Account(id = AccountId(20), name = "Nikolay Metchev", openingDate = Clock.System.now())
    private val someoneElse = Account(id = AccountId(21), name = "Olga Zakharenko", openingDate = Clock.System.now())
    private val columns =
        listOf("Type", "Product", "Started Date", "Completed Date", "Description", "Amount", "Fee", "Currency", "State", "Balance")
            .mapIndexed { i, n -> CsvColumn(CsvColumnId(Uuid.random()), i, n) }

    private fun row(description: String) =
        CsvRow(
            rowIndex = 1,
            values = "TRANSFER|Current|2022-05-12 11:11:23|2022-05-12 11:11:23|$description|-3000.00|0.00|GBP|COMPLETED|10.00".split("|"),
        )

    private fun map(description: String): MappingResult =
        CsvTransferMapper(
            strategy = strategy,
            columns = columns,
            existingAccounts = listOf(revolut, holder, someoneElse).associateBy { it.name },
            existingCurrencies = mapOf(gbp.id to gbp),
            existingCurrenciesByCode = mapOf("GBP" to gbp),
            accountOwnerNames = mapOf(revolut.id to listOf("Nikolay Metchev")),
        ).mapRow(row(description))

    @Test
    fun `a payment to the account's own holder is a placeholder counterparty`() {
        val result = map("To Nikolay Metchev")
        assertIs<MappingResult.Success>(result)
        assertEquals(holder.id, result.transfer.targetAccountId)
        assertEquals(holder.id, result.unidentifiedCounterpartyAccountId)
    }

    @Test
    fun `a payment to someone else is not`() {
        val result = map("To Olga Zakharenko")
        assertIs<MappingResult.Success>(result)
        assertEquals(someoneElse.id, result.transfer.targetAccountId)
        assertNull(result.unidentifiedCounterpartyAccountId)
    }
}
