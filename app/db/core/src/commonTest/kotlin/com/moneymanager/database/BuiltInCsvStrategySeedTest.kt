@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BuiltInCsvStrategySeedTest : DbTest() {
    @Test
    fun `a fresh database seeds the built-in Wise CSV strategy`() =
        runTest {
            val strategy =
                repositories.csvImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Wise CSV" }

            // The strategy auto-matches Wise's transaction-history.csv header
            val wiseHeader =
                setOf(
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
            assertTrue(strategy.matchesColumns(wiseHeader))

            // The new mapping types and row rules survive the JSON round trip through the database
            val source = strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]
            assertIs<TemplateAccountMapping>(source)
            assertEquals("Wise: ", source.prefix)
            assertEquals("Source currency", source.columnName)

            val target = strategy.fieldMappings[TransferField.TARGET_ACCOUNT]
            assertIs<ConditionalAccountMapping>(target)
            assertIs<TemplateAccountMapping>(target.whenTrue)

            val timestamp = strategy.fieldMappings[TransferField.TIMESTAMP]
            assertIs<DateTimeParsingMapping>(timestamp)
            assertEquals("yyyy-MM-dd HH:mm:ss", timestamp.dateTimeFormat)

            // OUT rows add the source fee to the debit (the amount column is net of fees)
            val amount = strategy.fieldMappings[TransferField.AMOUNT]
            assertIs<AmountParsingMapping>(amount)
            assertEquals("Source fee amount", amount.feeColumnName)

            val swapRule = strategy.rowPreprocessingRules.single()
            assertTrue(swapRule.flipSourceAndTarget)
            assertEquals(3, swapRule.columnSwaps.size)

            // The Wise transaction ID drives duplicate detection on re-import
            val idMapping = strategy.attributeMappings.single { it.columnName == "ID" }
            assertTrue(idMapping.isUniqueIdentifier)
        }
}
