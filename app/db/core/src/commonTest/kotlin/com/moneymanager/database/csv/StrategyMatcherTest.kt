@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

class StrategyMatcherTest {
    private fun createTestStrategy(
        name: String,
        identificationColumns: Set<String>,
    ): CsvImportStrategy {
        val now = Clock.System.now()
        return CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = name,
            identificationColumns = identificationColumns,
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.SOURCE_ACCOUNT,
                            accountId = AccountId(1),
                        ),
                    TransferField.TARGET_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            accountId = AccountId(2),
                        ),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMESTAMP,
                            dateColumnName = "Date",
                            dateFormat = "dd/MM/yyyy",
                        ),
                    TransferField.DESCRIPTION to
                        DirectColumnMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.DESCRIPTION,
                            columnName = "Description",
                        ),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = "Amount",
                        ),
                    TransferField.CURRENCY to
                        HardCodedCurrencyMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.CURRENCY,
                            currencyId = CurrencyId(Uuid.random()),
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `findMatchingStrategy returns matching strategy when columns match exactly`() {
        val strategy = createTestStrategy("Bank Statement", setOf("Date", "Description", "Amount"))
        val csvHeadings = listOf("Date", "Description", "Amount")

        val result = StrategyMatcher.findMatchingStrategy(csvHeadings, listOf(strategy))

        assertNotNull(result)
        assertEquals("Bank Statement", result.name)
    }

    @Test
    fun `findMatchingStrategy returns matching strategy regardless of column order`() {
        val strategy = createTestStrategy("Bank Statement", setOf("Date", "Description", "Amount"))
        val csvHeadings = listOf("Amount", "Date", "Description")

        val result = StrategyMatcher.findMatchingStrategy(csvHeadings, listOf(strategy))

        assertNotNull(result)
        assertEquals("Bank Statement", result.name)
    }

    @Test
    fun `findMatchingStrategy returns null when columns do not match`() {
        val strategy = createTestStrategy("Bank Statement", setOf("Date", "Description", "Amount"))
        val csvHeadings = listOf("Date", "Memo", "Value")

        val result = StrategyMatcher.findMatchingStrategy(csvHeadings, listOf(strategy))

        assertNull(result)
    }

    @Test
    fun `findMatchingStrategy returns null when CSV has extra columns`() {
        val strategy = createTestStrategy("Bank Statement", setOf("Date", "Description", "Amount"))
        val csvHeadings = listOf("Date", "Description", "Amount", "Balance")

        val result = StrategyMatcher.findMatchingStrategy(csvHeadings, listOf(strategy))

        assertNull(result)
    }

    @Test
    fun `findMatchingStrategy returns null when CSV has fewer columns`() {
        val strategy = createTestStrategy("Bank Statement", setOf("Date", "Description", "Amount"))
        val csvHeadings = listOf("Date", "Description")

        val result = StrategyMatcher.findMatchingStrategy(csvHeadings, listOf(strategy))

        assertNull(result)
    }

    @Test
    fun `findMatchingStrategy returns first matching strategy when multiple match`() {
        val strategy1 = createTestStrategy("Strategy 1", setOf("Date", "Amount"))
        val strategy2 = createTestStrategy("Strategy 2", setOf("Date", "Amount"))
        val csvHeadings = listOf("Date", "Amount")

        val result = StrategyMatcher.findMatchingStrategy(csvHeadings, listOf(strategy1, strategy2))

        assertNotNull(result)
        assertEquals("Strategy 1", result.name)
    }

    @Test
    fun `findMatchingStrategy returns null when no strategies provided`() {
        val csvHeadings = listOf("Date", "Description", "Amount")

        val result = StrategyMatcher.findMatchingStrategy(csvHeadings, emptyList())

        assertNull(result)
    }

    @Test
    fun `findAllMatchingStrategies returns all matching strategies`() {
        val strategy1 = createTestStrategy("Strategy 1", setOf("Date", "Amount"))
        val strategy2 = createTestStrategy("Strategy 2", setOf("Date", "Amount"))
        val strategy3 = createTestStrategy("Strategy 3", setOf("Date", "Description"))
        val csvHeadings = listOf("Date", "Amount")

        val result =
            StrategyMatcher.findAllMatchingStrategies(
                csvHeadings,
                listOf(strategy1, strategy2, strategy3),
            )

        assertEquals(2, result.size)
        assertEquals("Strategy 1", result[0].name)
        assertEquals("Strategy 2", result[1].name)
    }

    @Test
    fun `findAllMatchingStrategies returns empty list when no matches`() {
        val strategy = createTestStrategy("Strategy", setOf("Foo", "Bar"))
        val csvHeadings = listOf("Date", "Amount")

        val result = StrategyMatcher.findAllMatchingStrategies(csvHeadings, listOf(strategy))

        assertEquals(0, result.size)
    }
}
