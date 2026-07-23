package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ApplyStrategyDialogTest {
    @Test
    fun `buildPendingAccountMappings creates exact and regex mappings for selected accounts`() {
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
                                        csvValue = "ACME LTD",
                                        targetAccountName = "Acme",
                                    ),
                                ),
                                transferWithDiscoveredMapping(
                                    DiscoveredAccountMapping(
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
                accountSelections = mapOf("Acme" to selectedAccountId),
                now = now,
            )

        assertEquals(2, mappings.size)
        assertTrue(mappings.any { it.valuePattern.pattern == "^\\QACME LTD\\E$" })
        assertTrue(mappings.any { it.valuePattern.pattern == ".*ACME.*" })
        assertTrue(mappings.all { it.accountId == selectedAccountId })
        assertTrue(mappings.all { it.createdAt == now && it.updatedAt == now })
    }

    @Test
    fun `buildPendingAccountMappings deduplicates duplicate discovered mappings`() {
        val selectedAccountId = AccountId(99)

        val duplicateMapping =
            DiscoveredAccountMapping(
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
                accountSelections = mapOf("Coffee Shop" to selectedAccountId),
            )

        assertEquals(1, mappings.size)
        assertEquals("^\\QCoffee Shop\\E$", mappings.single().valuePattern.pattern)
    }

    @Test
    fun `buildPendingAccountMappings skips exact match when value equals chosen account name`() {
        val chosenId = AccountId(7)
        val chosen = Account(id = chosenId, name = "Coffee Shop", openingDate = Instant.fromEpochMilliseconds(0))

        val mappings =
            buildPendingAccountMappings(
                preparation =
                    ImportPreparation(
                        validTransfers =
                            listOf(
                                transferWithDiscoveredMapping(
                                    DiscoveredAccountMapping(
                                        csvValue = "Coffee Shop",
                                        targetAccountName = "Coffee Shop",
                                    ),
                                ),
                            ),
                        errorRows = emptyList(),
                        newAccounts = emptySet(),
                        existingAccountMatches = emptyMap(),
                    ),
                accountSelections = mapOf("Coffee Shop" to chosenId),
                accountsById = mapOf(chosenId to chosen),
            )

        // Redundant with plain name lookup, so no mapping is stored.
        assertTrue(mappings.isEmpty())
    }

    @Test
    fun `buildPendingAccountMappings keeps exact match when value differs from chosen account name`() {
        val chosenId = AccountId(7)
        val chosen = Account(id = chosenId, name = "Acme", openingDate = Instant.fromEpochMilliseconds(0))

        val mappings =
            buildPendingAccountMappings(
                preparation =
                    ImportPreparation(
                        validTransfers =
                            listOf(
                                transferWithDiscoveredMapping(
                                    DiscoveredAccountMapping(
                                        csvValue = "ACME LTD",
                                        targetAccountName = "Acme",
                                    ),
                                ),
                            ),
                        errorRows = emptyList(),
                        newAccounts = emptySet(),
                        existingAccountMatches = emptyMap(),
                    ),
                accountSelections = mapOf("Acme" to chosenId),
                accountsById = mapOf(chosenId to chosen),
            )

        assertEquals(1, mappings.size)
        assertEquals("^\\QACME LTD\\E$", mappings.single().valuePattern.pattern)
        assertEquals(chosenId, mappings.single().accountId)
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
    fun `hasBlankNewAccountNames detects create selections with explicitly blank names`() {
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
                newAccountNames = mapOf("Acme" to "   "),
            )

        assertTrue(hasBlank)
    }

    @Test
    fun `hasBlankNewAccountNames treats missing entries as keeping the detected name`() {
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

        assertFalse(hasBlank)
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
                            asset =
                                Currency(
                                    id = CurrencyId(1),
                                    code = "GBP",
                                    name = "British Pound",
                                ),
                        ),
                ),
            attributes = emptyList(),
            importStatus = ImportStatus.IMPORTED,
            rowIndex = 0,
            discoveredMappings = listOf(discoveredMapping),
        )
}
