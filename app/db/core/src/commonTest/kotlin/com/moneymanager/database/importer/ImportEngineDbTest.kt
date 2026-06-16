@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.importer
import com.moneymanager.database.SampleGeneratorSourceRecorder
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntityProvenance
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.importer.ImportEngineImpl
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportProvenance
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ImportEngineDbTest : DbTest() {
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun engine() =
        ImportEngineImpl(
            transactionRepository = repositories.transactionRepository,
            accountRepository = repositories.accountRepository,
            accountAttributeRepository = repositories.accountAttributeRepository,
            personRepository = repositories.personRepository,
            personAttributeRepository = repositories.personAttributeRepository,
            ownershipRepository = repositories.personAccountOwnershipRepository,
        )

    private fun testProvenance(): ImportProvenance {
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
        return object : ImportProvenance {
            override fun transferRecorder(orderedRowKeys: List<ImportRowKey>): SourceRecorder =
                SampleGeneratorSourceRecorder(transferSourceQueries, deviceId)

            override fun entityProvenance(): EntityProvenance = EntityProvenance.SampleGenerator(deviceId)
        }
    }

    private suspend fun gbp(): Currency =
        repositories.currencyRepository
            .getAllCurrencies()
            .first()
            .first { it.code == "GBP" }

    private suspend fun createSourceAccount(): AccountId {
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Checking", openingDate = baseTime))
        return repositories.accountRepository
            .getAllAccounts()
            .first()
            .first { it.name == "Checking" }
            .id
    }

    private fun batchWithCounterparty(
        sourceId: AccountId,
        currency: Currency,
        description: String,
        provenance: ImportProvenance,
        counterpartyKey: String = "coffee-shop",
        counterpartyName: String = "Coffee Shop",
    ): ImportBatch {
        val counterparty = LocalAccountKey(counterpartyKey)
        return ImportBatch(
            transfers =
                listOf(
                    ImportTransfer(
                        rowKey = ImportRowKey.CsvRow(0),
                        source = AccountRef.Existing(sourceId),
                        target = AccountRef.Local(counterparty),
                        timestamp = baseTime,
                        description = description,
                        amount = Money(500, currency),
                    ),
                ),
            dedupePolicy = DedupePolicy.FuzzyAllFields(),
            provenance = provenance,
            accountsToCreate =
                listOf(
                    ImportAccountIntent(
                        key = counterparty,
                        match = AccountMatchKey.ByName(counterpartyName),
                        name = counterpartyName,
                        openingDate = baseTime,
                    ),
                ),
        )
    }

    @Test
    fun createsAccountAndTransfer_thenDedupesOnReimport() =
        runTest {
            val sourceId = createSourceAccount()
            val currency = gbp()
            val provenance = testProvenance()

            val first = engine().import(batchWithCounterparty(sourceId, currency, "Coffee", provenance))
            assertEquals(1, first.accountsCreated)
            assertEquals(1, first.transfersImported)
            assertEquals(0, first.duplicates)

            // Re-import the identical batch: counterparty reused by name, transfer deduped.
            val second = engine().import(batchWithCounterparty(sourceId, currency, "Coffee", provenance))
            assertEquals(0, second.accountsCreated)
            assertEquals(0, second.transfersImported)
            assertEquals(1, second.duplicates)

            val coffeeShopId =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Coffee Shop" }
                    .id
            val transfers = repositories.transactionRepository.getTransactionsByAccount(coffeeShopId).first()
            assertEquals(1, transfers.size)
        }

    @Test
    fun dissimilarTransfer_isImportedNotDeduped() =
        runTest {
            val sourceId = createSourceAccount()
            val currency = gbp()
            val provenance = testProvenance()

            engine().import(
                batchWithCounterparty(sourceId, currency, "Tesco groceries weekly shop", provenance, "tesco", "Tesco"),
            )

            // A genuinely different description (low similarity) must NOT be treated as a fuzzy duplicate.
            val changedBatch =
                batchWithCounterparty(sourceId, currency, "British Gas direct debit", provenance, "tesco", "Tesco")
            val result = engine().import(changedBatch)
            assertEquals(1, result.transfersImported)
            assertEquals(0, result.duplicates)
            assertEquals(0, result.accountsCreated) // counterparty reused by name

            val tescoId =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Tesco" }
                    .id
            val transfers = repositories.transactionRepository.getTransactionsByAccount(tescoId).first()
            assertEquals(2, transfers.size)
        }

    @Test
    fun createsPeopleAndOwnership() =
        runTest {
            val sourceId = createSourceAccount()
            val currency = gbp()
            val provenance = testProvenance()
            val aliceKey = LocalPersonKey("alice")
            val bobKey = LocalPersonKey("bob")

            val batch =
                batchWithCounterparty(sourceId, currency, "Coffee", provenance).copy(
                    peopleToCreate =
                        listOf(
                            ImportPersonIntent(
                                key = aliceKey,
                                match = PersonMatchKey.ByNameKey("alice smith"),
                                firstName = "Alice",
                                lastName = "Smith",
                            ),
                            ImportPersonIntent(
                                key = bobKey,
                                match = PersonMatchKey.ByNameKey("bob jones"),
                                firstName = "Bob",
                                lastName = "Jones",
                            ),
                        ),
                    ownerships =
                        listOf(
                            ImportOwnershipIntent(aliceKey, AccountRef.Existing(sourceId)),
                            ImportOwnershipIntent(bobKey, AccountRef.Existing(sourceId)),
                        ),
                )

            val result = engine().import(batch)
            assertEquals(2, result.peopleCreated)
            assertEquals(2, result.ownershipsCreated)

            val people = repositories.personRepository.getAllPeople().first()
            assertEquals(2, people.size)
            assertEquals(setOf("Alice Smith", "Bob Jones"), people.map { it.fullName }.toSet())
            val ownerships = repositories.personAccountOwnershipRepository.getOwnershipsByAccount(sourceId).first()
            assertEquals(2, ownerships.size)
        }
}
