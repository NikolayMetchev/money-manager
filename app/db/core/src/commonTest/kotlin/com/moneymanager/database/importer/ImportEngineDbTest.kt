@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.importer
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importer.ImportEngineImpl
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
        source: Source,
        counterpartyKey: String = "coffee-shop",
        counterpartyName: String = "Coffee Shop",
    ): ImportBatch {
        val counterparty = LocalAccountKey(counterpartyKey)
        return ImportBatch(
            transfers =
                listOf(
                    ImportTransfer(
                        rowKey = ImportRowKey.CsvRow(0),
                        fromAccount = AccountRef.Existing(sourceId),
                        toAccount = AccountRef.Local(counterparty),
                        source = source,
                        timestamp = baseTime,
                        description = description,
                        amount = Money(500, currency),
                    ),
                ),
            dedupePolicy = DedupePolicy.FuzzyAllFields(),
            accountsToCreate =
                listOf(
                    ImportAccountIntent(
                        key = counterparty,
                        match = AccountMatchKey.ByName(counterpartyName),
                        name = counterpartyName,
                        openingDate = baseTime,
                        source = source,
                    ),
                ),
        )
    }

    @Test
    fun createsAccountAndTransfer_thenDedupesOnReimport() =
        runTest {
            val sourceId = createSourceAccount()
            val currency = gbp()
            val source = Source.SampleGenerator

            val first = engine().import(batchWithCounterparty(sourceId, currency, "Coffee", source))
            assertEquals(1, first.accountsCreated)
            assertEquals(1, first.transfersImported)
            assertEquals(0, first.duplicates)

            // Re-import the identical batch: counterparty reused by name, transfer deduped.
            val second = engine().import(batchWithCounterparty(sourceId, currency, "Coffee", source))
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
            val source = Source.SampleGenerator

            engine().import(
                batchWithCounterparty(sourceId, currency, "Tesco groceries weekly shop", source, "tesco", "Tesco"),
            )

            // A genuinely different description (low similarity) must NOT be treated as a fuzzy duplicate.
            val changedBatch =
                batchWithCounterparty(sourceId, currency, "British Gas direct debit", source, "tesco", "Tesco")
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
            val source = Source.SampleGenerator
            val aliceKey = LocalPersonKey("alice")
            val bobKey = LocalPersonKey("bob")

            val batch =
                batchWithCounterparty(sourceId, currency, "Coffee", source).copy(
                    peopleToCreate =
                        listOf(
                            ImportPersonIntent(
                                key = aliceKey,
                                match = PersonMatchKey.ByNameKey("alice smith"),
                                firstName = "Alice",
                                lastName = "Smith",
                                source = source,
                            ),
                            ImportPersonIntent(
                                key = bobKey,
                                match = PersonMatchKey.ByNameKey("bob jones"),
                                firstName = "Bob",
                                lastName = "Jones",
                                source = source,
                            ),
                        ),
                    ownerships =
                        listOf(
                            ImportOwnershipIntent(aliceKey, AccountRef.Existing(sourceId), source),
                            ImportOwnershipIntent(bobKey, AccountRef.Existing(sourceId), source),
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
