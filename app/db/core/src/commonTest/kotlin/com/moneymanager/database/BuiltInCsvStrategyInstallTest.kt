@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.installBuiltInCsvStrategies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Built-in strategies are no longer seeded; installing them through the engine (the same
// path a catalog install takes) must survive the JSON round trip through the database.
class BuiltInCsvStrategyInstallTest : DbTest() {
    @Test
    fun `installing the built-in Wise CSV strategy round-trips through the database`() =
        runTest {
            repositories.installBuiltInCsvStrategies()
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

            // Assets fees require a manually entered interest transfer (companion rule
            // survives the JSON round trip through the database)
            val companionRule = strategy.companionTransactionRules.single()
            assertEquals("Interest earned", companionRule.name)
            assertEquals("wise-id", companionRule.matchAttributeName)
            assertEquals("ACCRUAL_CHARGE-%", companionRule.matchValuePattern)
            assertEquals("wise-interest-for", companionRule.linkAttributeName)
            assertEquals("Interest earned", companionRule.companionDescription)
        }

    @Test
    fun `installing the built-in Monzo CSV strategy round-trips through the database`() =
        runTest {
            repositories.installBuiltInCsvStrategies()
            val strategy =
                repositories.csvImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo CSV" }

            // The strategy auto-matches Monzo's transaction export header
            val monzoHeader =
                setOf(
                    "Transaction ID",
                    "Date",
                    "Time",
                    "Type",
                    "Name",
                    "Emoji",
                    "Category",
                    "Amount",
                    "Currency",
                    "Local amount",
                    "Local currency",
                    "Notes and #tags",
                    "Address",
                    "Receipt",
                    "Description",
                    "Category split",
                    "Money Out",
                    "Money In",
                )
            assertTrue(strategy.matchesColumns(monzoHeader))

            // No source account mapping is defined (account ids are database-specific);
            // the user picks the Monzo account when applying the strategy
            assertNull(strategy.fieldMappings[TransferField.SOURCE_ACCOUNT])

            // Positive amounts flow INTO the account, so credits flip source/target
            val amount = strategy.fieldMappings[TransferField.AMOUNT]
            assertIs<AmountParsingMapping>(amount)
            assertTrue(amount.flipAccountsOnPositive)

            // The Monzo transaction ID drives duplicate detection on re-import
            val idMapping = strategy.attributeMappings.single { it.columnName == "Transaction ID" }
            assertTrue(idMapping.isUniqueIdentifier)
        }

    @Test
    fun `installing the built-in Binance CSV strategy round-trips through the database`() =
        runTest {
            repositories.installBuiltInCsvStrategies()
            val strategy =
                repositories.csvImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Binance CSV" }

            // The modern export's header, and only it: the legacy one lacks User_ID.
            assertTrue(
                strategy.matchesColumns(setOf("User_ID", "UTC_Time", "Account", "Operation", "Coin", "Change", "Remark")),
            )
            assertTrue(
                !strategy.matchesColumns(setOf("UTC_Time", "Account", "Operation", "Coin", "Change", "Remark")),
                "the legacy 6-column header is not an exact match",
            )
            // The content rule is what keeps a legacy file out via the tolerant subset path.
            assertEquals("User_ID", strategy.contentMatchRules.single().columnName)

            // Trade-group assembly survives the round trip - without it the export's trade rows would
            // import as suspense transfers instead of trades.
            val tradeGroup = assertNotNull(strategy.tradeGroupConfig)
            assertEquals("Operation", tradeGroup.signalColumn)
            assertEquals("Change", tradeGroup.sideAmountColumn, "the ambiguous leg name is resolved by sign")
            assertEquals(0L, tradeGroup.groupingWindowSeconds)

            // So does the dust conversion config, including the sign-based side classification.
            val conversion = assertNotNull(strategy.conversionConfig)
            assertEquals("Binance Conversions", conversion.conversionAccountName)
            assertEquals("Change", conversion.sideAmountColumn)
            assertEquals(conversion.debitPattern, conversion.creditPattern, "both dust legs share one Operation")

            // Fiat and crypto funding split to the two accounts the API strategy also creates.
            val target = strategy.fieldMappings[TransferField.TARGET_ACCOUNT]
            assertIs<ConditionalAccountMapping>(target)

            val amount = strategy.fieldMappings[TransferField.AMOUNT]
            assertIs<AmountParsingMapping>(amount)
            assertTrue(amount.flipAccountsOnPositive, "a positive Change arrives into the Binance account")

            val timestamp = strategy.fieldMappings[TransferField.TIMESTAMP]
            assertIs<DateTimeParsingMapping>(timestamp)
            assertEquals("yyyy-MM-dd HH:mm:ss", timestamp.dateTimeFormat)
        }
}
