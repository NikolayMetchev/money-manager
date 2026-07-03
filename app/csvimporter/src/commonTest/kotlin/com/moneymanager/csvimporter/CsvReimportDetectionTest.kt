@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferRelationship
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
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
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.model.passthrough.PassThroughRule
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.importengineapi.PassThroughDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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

    private fun strategy(flipAccountsOnPositive: Boolean = false): CsvImportStrategy {
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
                            flipAccountsOnPositive = flipAccountsOnPositive,
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
        passThroughAccounts: List<PassThroughAccount> = emptyList(),
        flipAccountsOnPositive: Boolean = false,
    ): ImportPreparation =
        CsvTransferMapper(
            strategy = strategy(flipAccountsOnPositive),
            columns = columns,
            existingAccounts = accounts.associateBy { it.name },
            existingCurrencies = mapOf(currencyId to currency),
            existingCurrenciesByCode = mapOf(currency.code.uppercase() to currency),
            accountMappings = accountMappings,
            historicalAccountNames = historicalAccountNames,
            passThroughDetector = passThroughAccounts.takeIf { it.isNotEmpty() }?.let { PassThroughDetector(it) },
        ).prepareImport(rows)

    private fun row(
        index: Long,
        payee: String,
        description: String = "Payment",
        amount: String = "-50.00",
    ) = CsvRow(rowIndex = index, values = listOf("15/12/2024", description, amount, payee))

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

    // ============= Pass-through merchant detection =============

    // Mirrors the seeded Curve rule: the transfer routes through the conduit on both preps, so a
    // mapping applied to the stripped merchant is only visible on the pass-through merchant account.
    private val curve =
        PassThroughAccount(
            id = PassThroughAccountId(1),
            name = "Curve",
            conduitAccountName = "Curve",
            rules =
                listOf(
                    PassThroughRule(
                        detectionPattern = "(?i)^(?:Refund: )?CRV\\*",
                        merchantPattern = "(?i)^(?:Refund: )?CRV\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                    ),
                ),
        )

    @Test
    fun `new mapping consolidating an import-created pass-through merchant yields a merge candidate`() {
        val conduit = account(5, "Curve")
        val merchantDup = account(10, "Amazoncouk 1234")
        val amazon = account(20, "Amazon")
        val accounts = listOf(conduit, merchantDup, amazon)
        val rows = listOf(row(1, "Crv*Amazoncouk 1234", description = "Crv*Amazoncouk 1234"))
        val mappings = listOf(mapping(1, ".*Amazoncouk.*", amazon.id))

        val candidates =
            computeReimportMerges(
                baselinePrep = prep(rows, accounts, emptyList(), passThroughAccounts = listOf(curve)),
                mappedPrep = prep(rows, accounts, mappings, passThroughAccounts = listOf(curve)),
                importCreatedAccounts = setOf(merchantDup.id),
            )

        assertEquals(mapOf(merchantDup.id to amazon.id), candidates.merges)
        assertTrue(candidates.conflicts.isEmpty())
    }

    @Test
    fun `pass-through merchant rows resolving to different targets are a conflict`() {
        val conduit = account(5, "Curve")
        val merchantDup = account(10, "Amazoncouk")
        val targetA = account(20, "Amazon")
        val targetB = account(30, "Amazon Prime")
        val accounts = listOf(conduit, merchantDup, targetA, targetB)
        val rows =
            listOf(
                row(1, "Crv*Amazoncouk order", description = "Crv*Amazoncouk order"),
                row(2, "Crv*Amazoncouk subscription", description = "Crv*Amazoncouk subscription"),
            )
        val mappings =
            listOf(
                mapping(1, "order", targetA.id),
                mapping(2, "subscription", targetB.id),
            )
        // Both stripped merchant strings were historical names of the same import-created account.
        val historical =
            mapOf(
                "amazoncouk order" to merchantDup.id,
                "amazoncouk subscription" to merchantDup.id,
            )

        val candidates =
            computeReimportMerges(
                baselinePrep = prep(rows, accounts, emptyList(), historical, passThroughAccounts = listOf(curve)),
                mappedPrep = prep(rows, accounts, mappings, historical, passThroughAccounts = listOf(curve)),
                importCreatedAccounts = setOf(merchantDup.id),
            )

        assertTrue(candidates.merges.isEmpty())
        assertEquals(mapOf(merchantDup.id to setOf(targetA.id, targetB.id)), candidates.conflicts)
    }

    @Test
    fun `pass-through merchant not created by this import produces no candidate`() {
        val conduit = account(5, "Curve")
        val merchant = account(10, "Amazoncouk 1234")
        val amazon = account(20, "Amazon")
        val accounts = listOf(conduit, merchant, amazon)
        val rows = listOf(row(1, "Crv*Amazoncouk 1234", description = "Crv*Amazoncouk 1234"))
        val mappings = listOf(mapping(1, ".*Amazoncouk.*", amazon.id))

        val candidates =
            computeReimportMerges(
                baselinePrep = prep(rows, accounts, emptyList(), passThroughAccounts = listOf(curve)),
                mappedPrep = prep(rows, accounts, mappings, passThroughAccounts = listOf(curve)),
                importCreatedAccounts = emptySet(),
            )

        assertTrue(candidates.merges.isEmpty())
        assertTrue(candidates.conflicts.isEmpty())
    }

    @Test
    fun `incoming pass-through refund still yields the merchant merge candidate`() {
        val conduit = account(5, "Curve")
        val merchantDup = account(10, "Amazoncouk 1234")
        val amazon = account(20, "Amazon")
        val accounts = listOf(conduit, merchantDup, amazon)
        // Positive amount + flipAccountsOnPositive marks the row incoming (refund onto the card).
        val rows = listOf(row(1, "Refund: Crv*Amazoncouk 1234", description = "Refund: Crv*Amazoncouk 1234", amount = "50.00"))
        val mappings = listOf(mapping(1, ".*Amazoncouk.*", amazon.id))

        val candidates =
            computeReimportMerges(
                baselinePrep =
                    prep(rows, accounts, emptyList(), passThroughAccounts = listOf(curve), flipAccountsOnPositive = true),
                mappedPrep =
                    prep(rows, accounts, mappings, passThroughAccounts = listOf(curve), flipAccountsOnPositive = true),
                importCreatedAccounts = setOf(merchantDup.id),
            )

        assertEquals(mapOf(merchantDup.id to amazon.id), candidates.merges)
    }

    // ============= Retroactive pass-through rewrites =============

    /** In-memory relationship store: transferId -> outgoing relationships (id1 = that transfer). */
    private class FakeRelationshipRepository(
        private val byTransfer: Map<TransferId, List<TransferRelationship>>,
    ) : TransferRelationshipReadRepository {
        override fun getByTransfer(transferId: TransferId): Flow<List<TransferRelationship>> = flowOf(byTransfer[transferId].orEmpty())
    }

    private val passThroughType = RelationshipType(RelationshipTypeId(3), "pass-through")

    private fun importedRow(
        index: Long,
        payee: String,
        transferId: Long,
    ) = CsvRow(
        rowIndex = index,
        values = listOf("15/12/2024", payee, "-50.00", payee),
        transferId = TransferId(transferId),
        importStatus = ImportStatus.IMPORTED,
    )

    @Test
    fun `imported row without pass-through legs is rewritten when detection now routes it`() =
        runTest {
            val accounts = listOf(account(5, "Curve"), account(10, "Crv*Amazoncouk 1234"))
            // The row was imported flat (card -> raw merchant, no legs) before the definition existed.
            val rows = listOf(importedRow(1, "Crv*Amazoncouk 1234", transferId = 100))
            val mappedPrep = prep(rows, accounts, emptyList(), passThroughAccounts = listOf(curve))

            val rewrites =
                computeReimportRewrites(rows, mappedPrep, FakeRelationshipRepository(emptyMap()))

            val rewrite = rewrites.single()
            assertEquals(1L, rewrite.rowIndex)
            assertEquals(listOf(TransferId(100)), rewrite.transferIdsToDelete)
            assertEquals(listOf("Curve"), rewrite.conduitNames)
            assertEquals("Amazoncouk 1234", rewrite.merchantName)
        }

    @Test
    fun `imported row whose legs already match the chain is left alone`() =
        runTest {
            val accounts = listOf(account(5, "Curve"), account(10, "Amazoncouk 1234"))
            val rows = listOf(importedRow(1, "Crv*Amazoncouk 1234", transferId = 100))
            val mappedPrep = prep(rows, accounts, emptyList(), passThroughAccounts = listOf(curve))
            // The funding transfer already carries its single pass-through spend leg.
            val relationships =
                mapOf(
                    TransferId(100) to listOf(TransferRelationship(TransferId(100), TransferId(101), passThroughType)),
                )

            val rewrites = computeReimportRewrites(rows, mappedPrep, FakeRelationshipRepository(relationships))

            assertTrue(rewrites.isEmpty())
        }

    @Test
    fun `single-hop row is rewritten when the chain is now longer, deleting the old leg too`() =
        runTest {
            val paypal =
                PassThroughAccount(
                    id = PassThroughAccountId(2),
                    name = "PayPal",
                    conduitAccountName = "PayPal",
                    rules =
                        listOf(
                            PassThroughRule(
                                detectionPattern = "(?i)^PAYPAL\\s*\\*",
                                merchantPattern = "(?i)^PAYPAL\\s*\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                            ),
                        ),
                )
            val accounts = listOf(account(5, "Curve"), account(6, "PayPal"), account(10, "Paypal *Thepihut 0"))
            val rows = listOf(importedRow(1, "Crv*Paypal *Thepihut 0", transferId = 100))
            val mappedPrep = prep(rows, accounts, emptyList(), passThroughAccounts = listOf(curve, paypal))
            // Previously imported as a single hop: funding -> one spend leg only.
            val relationships =
                mapOf(
                    TransferId(100) to listOf(TransferRelationship(TransferId(100), TransferId(101), passThroughType)),
                )

            val rewrites = computeReimportRewrites(rows, mappedPrep, FakeRelationshipRepository(relationships))

            val rewrite = rewrites.single()
            assertEquals(listOf("Curve", "PayPal"), rewrite.conduitNames)
            // Both the funding transfer and its old spend leg are deleted before the re-run.
            assertEquals(listOf(TransferId(100), TransferId(101)), rewrite.transferIdsToDelete)
        }

    @Test
    fun `duplicate and unimported rows are never rewritten`() =
        runTest {
            val accounts = listOf(account(5, "Curve"))
            val rows =
                listOf(
                    // DUPLICATE: its transfer belongs to another row and must not be deleted.
                    CsvRow(
                        rowIndex = 1,
                        values = listOf("15/12/2024", "Crv*Amazoncouk 1234", "-50.00", "Crv*Amazoncouk 1234"),
                        transferId = TransferId(100),
                        importStatus = ImportStatus.DUPLICATE,
                    ),
                    // Never imported: applyStagedCsv picks it up anyway.
                    row(2, "Crv*Amazoncouk 1234", description = "Crv*Amazoncouk 1234"),
                )
            val mappedPrep = prep(rows, accounts, emptyList(), passThroughAccounts = listOf(curve))

            val rewrites = computeReimportRewrites(rows, mappedPrep, FakeRelationshipRepository(emptyMap()))

            assertTrue(rewrites.isEmpty())
        }
}
