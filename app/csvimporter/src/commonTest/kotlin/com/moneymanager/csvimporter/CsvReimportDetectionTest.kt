@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Tests for [computeReimportMerges]: re-import detects import-created duplicate accounts that the
 * current persisted mappings consolidate onto another account, by comparing the rows mapped without
 * mappings (baseline — resolves to the duplicates by name) against the rows mapped with them.
 */
class CsvReimportDetectionTest {
    private val currencyId = CurrencyId(1L)
    private val currency = Currency(id = currencyId, code = "GBP", name = "British Pound")
    private val sourceAccountId = AccountId(1)
    private val strategyId = CsvImportStrategyId(Uuid.random())

    private val columns =
        listOf(
            CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
            CsvColumn(CsvColumnId(Uuid.random()), 1, "Description"),
            CsvColumn(CsvColumnId(Uuid.random()), 2, "Amount"),
            CsvColumn(CsvColumnId(Uuid.random()), 3, "Payee"),
        )

    private fun strategy(): CsvImportStrategy {
        val now = Clock.System.now()
        return CsvImportStrategy(
            id = strategyId,
            name = "Test Strategy",
            identificationColumns = setOf("Date", "Description", "Amount"),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.SOURCE_ACCOUNT,
                            accountId = sourceAccountId,
                        ),
                    TransferField.TARGET_ACCOUNT to
                        AccountLookupMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = "Payee",
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
                            currencyId = currencyId,
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun account(
        id: Long,
        name: String,
    ) = Account(id = AccountId(id), name = name, openingDate = Clock.System.now())

    private fun mapping(
        id: Long,
        pattern: String,
        accountId: AccountId,
        strategyId: CsvImportStrategyId? = null,
    ): AccountMapping {
        val now = Clock.System.now()
        return AccountMapping(
            id = id,
            strategyId = strategyId,
            valuePattern = Regex(pattern, RegexOption.IGNORE_CASE),
            accountId = accountId,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun prep(
        rows: List<CsvRow>,
        accounts: List<Account>,
        accountMappings: List<AccountMapping>,
        historicalAccountNames: Map<String, AccountId> = emptyMap(),
    ): ImportPreparation =
        CsvTransferMapper(
            strategy = strategy(),
            columns = columns,
            existingAccounts = accounts.associateBy { it.name },
            existingCurrencies = mapOf(currencyId to currency),
            existingCurrenciesByCode = mapOf(currency.code.uppercase() to currency),
            accountMappings = accountMappings,
            historicalAccountNames = historicalAccountNames,
        ).prepareImport(rows)

    private fun row(
        index: Long,
        payee: String,
    ) = CsvRow(rowIndex = index, values = listOf("15/12/2024", "Payment", "-50.00", payee))

    @Test
    fun `new global mapping consolidating an import-created account yields a merge candidate`() {
        val duplicate = account(10, "Amazon.com")
        val trueAccount = account(20, "Amazon")
        val accounts = listOf(duplicate, trueAccount)
        val rows = listOf(row(1, "Amazon.com"), row(2, "Amazon.com"))
        val mappings = listOf(mapping(1, "^Amazon\\.com$", trueAccount.id))

        val candidates =
            computeReimportMerges(
                baselinePrep = prep(rows, accounts, emptyList()),
                mappedPrep = prep(rows, accounts, mappings),
                importCreatedAccounts = setOf(duplicate.id),
            )

        assertEquals(mapOf(duplicate.id to trueAccount.id), candidates.merges)
        assertTrue(candidates.conflicts.isEmpty())
    }

    @Test
    fun `mapping scoped to a different strategy produces no candidate`() {
        val duplicate = account(10, "Amazon.com")
        val trueAccount = account(20, "Amazon")
        val accounts = listOf(duplicate, trueAccount)
        val rows = listOf(row(1, "Amazon.com"))
        val mappings = listOf(mapping(1, "^Amazon\\.com$", trueAccount.id, strategyId = CsvImportStrategyId(Uuid.random())))

        val candidates =
            computeReimportMerges(
                baselinePrep = prep(rows, accounts, emptyList()),
                mappedPrep = prep(rows, accounts, mappings),
                importCreatedAccounts = setOf(duplicate.id),
            )

        assertTrue(candidates.merges.isEmpty())
        assertTrue(candidates.conflicts.isEmpty())
    }

    @Test
    fun `mapping redirecting an account this import did not create produces no candidate`() {
        val duplicate = account(10, "Amazon.com")
        val trueAccount = account(20, "Amazon")
        val accounts = listOf(duplicate, trueAccount)
        val rows = listOf(row(1, "Amazon.com"))
        val mappings = listOf(mapping(1, "^Amazon\\.com$", trueAccount.id))

        val candidates =
            computeReimportMerges(
                baselinePrep = prep(rows, accounts, emptyList()),
                mappedPrep = prep(rows, accounts, mappings),
                // "Amazon.com" exists but was created manually or by another import.
                importCreatedAccounts = emptySet(),
            )

        assertTrue(candidates.merges.isEmpty())
        assertTrue(candidates.conflicts.isEmpty())
    }

    @Test
    fun `duplicate whose rows resolve to different targets is a conflict not a merge`() {
        val duplicate = account(10, "Amazon.com")
        val targetA = account(20, "Amazon")
        val targetB = account(30, "Amazon Prime")
        val accounts = listOf(duplicate, targetA, targetB)
        val rows = listOf(row(1, "Amazon.com order"), row(2, "Amazon.com subscription"))
        // Both rows resolve to "Amazon.com order"/"Amazon.com subscription"... use patterns splitting them.
        val mappings =
            listOf(
                mapping(1, "order", targetA.id),
                mapping(2, "subscription", targetB.id),
            )
        // Baseline: both payee values are new names; make them resolve to the SAME import-created
        // account by mapping them via historical names of the duplicate.
        val historical =
            mapOf(
                "amazon.com order" to duplicate.id,
                "amazon.com subscription" to duplicate.id,
            )

        val candidates =
            computeReimportMerges(
                baselinePrep = prep(rows, accounts, emptyList(), historical),
                mappedPrep = prep(rows, accounts, mappings, historical),
                importCreatedAccounts = setOf(duplicate.id),
            )

        assertTrue(candidates.merges.isEmpty())
        assertEquals(mapOf(duplicate.id to setOf(targetA.id, targetB.id)), candidates.conflicts)
    }

    @Test
    fun `renamed duplicate still detected via historical account names`() {
        // The import created "Amazon.com"; the user renamed it to "Shopping". Baseline resolution
        // falls back to the historical name and still lands on the import-created account.
        val renamedDuplicate = account(10, "Shopping")
        val trueAccount = account(20, "Amazon")
        val accounts = listOf(renamedDuplicate, trueAccount)
        val rows = listOf(row(1, "Amazon.com"))
        val mappings = listOf(mapping(1, "^Amazon\\.com$", trueAccount.id))
        val historical = mapOf("amazon.com" to renamedDuplicate.id)

        val candidates =
            computeReimportMerges(
                baselinePrep = prep(rows, accounts, emptyList(), historical),
                mappedPrep = prep(rows, accounts, mappings, historical),
                importCreatedAccounts = setOf(renamedDuplicate.id),
            )

        assertEquals(mapOf(renamedDuplicate.id to trueAccount.id), candidates.merges)
    }

    @Test
    fun `rows unaffected by mappings produce no candidates`() {
        val duplicate = account(10, "Amazon.com")
        val other = account(30, "Tesco")
        val trueAccount = account(20, "Amazon")
        val accounts = listOf(duplicate, other, trueAccount)
        val rows = listOf(row(1, "Amazon.com"), row(2, "Tesco"))
        val mappings = listOf(mapping(1, "^Amazon\\.com$", trueAccount.id))

        val candidates =
            computeReimportMerges(
                baselinePrep = prep(rows, accounts, emptyList()),
                mappedPrep = prep(rows, accounts, mappings),
                importCreatedAccounts = setOf(duplicate.id, other.id),
            )

        // Tesco resolves identically in both preparations, so only Amazon.com is a candidate.
        assertEquals(mapOf(duplicate.id to trueAccount.id), candidates.merges)
        assertTrue(candidates.conflicts.isEmpty())
    }
}
