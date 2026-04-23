@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.database.csv.CsvTransferWithAttributes
import com.moneymanager.database.csv.DiscoveredAccountMapping
import com.moneymanager.database.csv.ImportPreparation
import com.moneymanager.database.csv.NewAccount
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ApplyStrategyDialogTest {
    @Test
    fun `buildPendingAccountMappings creates exact and regex mappings for selected accounts`() {
        val strategyId = CsvImportStrategyId(Uuid.random())
        val selectedAccountId = AccountId(42)
        val now = Instant.fromEpochMilliseconds(1_000)

        val mappings =
            buildPendingAccountMappings(
                preparation =
                    ImportPreparation(
                        validTransfers =
                            listOf(
                                transferWithDiscoveredMapping(
                                    DiscoveredAccountMapping(
                                        columnName = "Payee",
                                        csvValue = "ACME LTD",
                                        targetAccountName = "Acme",
                                    ),
                                ),
                                transferWithDiscoveredMapping(
                                    DiscoveredAccountMapping(
                                        columnName = "Description",
                                        csvValue = "PAYMENT TO ACME",
                                        targetAccountName = "Acme",
                                        matchedPattern = ".*ACME.*",
                                    ),
                                ),
                            ),
                        errorRows = emptyList(),
                        newAccounts = emptySet(),
                        existingAccountMatches = emptyMap(),
                    ),
                strategyId = strategyId,
                accountSelections = mapOf("Acme" to selectedAccountId),
                now = now,
            )

        assertEquals(2, mappings.size)
        assertTrue(mappings.any { it.columnName == "Payee" && it.valuePattern.pattern == "^\\QACME LTD\\E$" })
        assertTrue(mappings.any { it.columnName == "Description" && it.valuePattern.pattern == ".*ACME.*" })
        assertTrue(mappings.all { it.accountId == selectedAccountId })
        assertTrue(mappings.all { it.strategyId == strategyId })
        assertTrue(mappings.all { it.createdAt == now && it.updatedAt == now })
    }

    @Test
    fun `buildPendingAccountMappings deduplicates duplicate discovered mappings`() {
        val strategyId = CsvImportStrategyId(Uuid.random())
        val selectedAccountId = AccountId(99)

        val duplicateMapping =
            DiscoveredAccountMapping(
                columnName = "Payee",
                csvValue = "Coffee Shop",
                targetAccountName = "Coffee Shop",
            )

        val mappings =
            buildPendingAccountMappings(
                preparation =
                    ImportPreparation(
                        validTransfers =
                            listOf(
                                transferWithDiscoveredMapping(duplicateMapping),
                                transferWithDiscoveredMapping(duplicateMapping),
                            ),
                        errorRows = emptyList(),
                        newAccounts = emptySet(),
                        existingAccountMatches = emptyMap(),
                    ),
                strategyId = strategyId,
                accountSelections = mapOf("Coffee Shop" to selectedAccountId),
            )

        assertEquals(1, mappings.size)
        assertEquals("^\\QCoffee Shop\\E$", mappings.single().valuePattern.pattern)
    }

    @Test
    fun `buildAccountsToCreate uses renamed account names and skips mapped accounts`() {
        val createdAccounts =
            buildAccountsToCreate(
                preparation =
                    ImportPreparation(
                        validTransfers = emptyList(),
                        errorRows = emptyList(),
                        newAccounts =
                            setOf(
                                NewAccount(name = "Acme", categoryId = 10),
                                NewAccount(name = "Coffee", categoryId = 20),
                            ),
                        existingAccountMatches = emptyMap(),
                    ),
                existingAccountSelections = mapOf("Coffee" to AccountId(55)),
                newAccountNames = mapOf("Acme" to "Acme Renamed"),
            )

        assertEquals(1, createdAccounts.size)
        assertEquals("Acme Renamed", createdAccounts.single().name)
        assertEquals(10L, createdAccounts.single().categoryId)
    }

    @Test
    fun `hasBlankNewAccountNames detects create selections without names`() {
        val hasBlank =
            hasBlankNewAccountNames(
                preparation =
                    ImportPreparation(
                        validTransfers = emptyList(),
                        errorRows = emptyList(),
                        newAccounts = setOf(NewAccount(name = "Acme", categoryId = 10)),
                        existingAccountMatches = emptyMap(),
                    ),
                existingAccountSelections = emptyMap(),
                newAccountNames = emptyMap(),
            )

        assertTrue(hasBlank)
    }

    @Test
    fun `buildCreatedAccountNameOverrides returns only trimmed overrides for unmapped new accounts`() {
        val overrides =
            buildCreatedAccountNameOverrides(
                preparation =
                    ImportPreparation(
                        validTransfers = emptyList(),
                        errorRows = emptyList(),
                        newAccounts =
                            setOf(
                                NewAccount(name = "Acme", categoryId = 10),
                                NewAccount(name = "Coffee", categoryId = 20),
                                NewAccount(name = "Ignored", categoryId = 30),
                            ),
                        existingAccountMatches = emptyMap(),
                    ),
                existingAccountSelections = mapOf("Coffee" to AccountId(55)),
                newAccountNames =
                    mapOf(
                        "Acme" to "  Acme Renamed  ",
                        "Coffee" to "Should Be Ignored",
                        "Ignored" to "   ",
                        "NotNew" to "Does Not Matter",
                    ),
            )

        assertEquals(mapOf("Acme" to "Acme Renamed"), overrides)
    }

    private fun transferWithDiscoveredMapping(discoveredMapping: DiscoveredAccountMapping): CsvTransferWithAttributes =
        CsvTransferWithAttributes(
            transfer =
                Transfer(
                    id = TransferId(0),
                    timestamp = Instant.fromEpochMilliseconds(0),
                    description = "Test",
                    sourceAccountId = AccountId(1),
                    targetAccountId = AccountId(-1),
                    amount =
                        Money.fromDisplayValue(
                            displayValue = BigDecimal("1.00"),
                            currency =
                                Currency(
                                    id = CurrencyId(1),
                                    code = "GBP",
                                    name = "British Pound",
                                    scaleFactor = 100,
                                ),
                        ),
                ),
            attributes = emptyList(),
            importStatus = ImportStatus.IMPORTED,
            existingTransferId = null,
            rowIndex = 0,
            discoveredMapping = discoveredMapping,
        )
}
