package com.moneymanager.cli

import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.installBuiltInApiStrategies
import com.moneymanager.test.database.installBuiltInCsvStrategies
import kotlinx.coroutines.flow.first
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A small database for the query commands' golden tests: two owners (and two people sharing a name), a
 * reconciliation source's shadow wallet, GBP and BTC transfers at fixed times, the built-in strategies and
 * an import directory. Seeded straight through the write repositories, as test fixtures may.
 */
internal suspend fun TestCli.seedQueryFixture(installStrategies: Boolean = false): DbLocation {
    val location = createTestDatabaseLocation()
    val database = databaseManager.openDatabase(location)
    try {
        val db = DatabaseComponent.create(database)
        val alice = db.person("Alice", "Smith")
        val bob = db.person("Bob", null)
        db.person("Sam", "Lee")
        db.person("Sam", "Lee")

        val current = db.account("Current")
        val saver = db.account("Current Saver")
        val wallet = db.account("Wallet")
        val koinly = db.account("Koinly Wallet")
        val employer = db.account("Employer")
        val shop = db.account("Corner Shop")
        db.owns(alice, listOf(current, saver, wallet))
        db.owns(bob, listOf(wallet))
        db.accountAttributeRepository.insert(koinly, AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID), "Koinly")

        val gbp = checkNotNull(db.currencyRepository.getCurrencyByCode("GBP").first())
        val btcId = db.cryptoRepository.upsertCryptoByCode("BTC", "Bitcoin", Source.Manual)
        val btc = checkNotNull(db.cryptoRepository.getCryptoAssetById(btcId).first())
        db.transactionRepository.createTransfers(
            listOf(
                transfer("2024-02-28T09:00:00Z", "Salary", employer, current, "1000", gbp),
                transfer("2024-03-01T10:00:00Z", "To wallet", current, wallet, "100", gbp),
                transfer("2024-03-02T12:30:00Z", "Milk", current, shop, "25.50", gbp),
                transfer("2024-03-03T08:00:00Z", "Save", current, saver, "10", gbp),
                transfer("2024-03-04T08:00:00Z", "Unsave", saver, current, "10", gbp),
                transfer("2024-03-05T15:00:00Z", "Buy BTC", employer, wallet, "0.00012345", btc),
                transfer("2024-03-05T15:00:00Z", "Koinly copy", employer, koinly, "0.00012345", btc),
            ),
            sources = List(7) { Source.Manual },
        )

        db.directory("Statements", ImportDirectoryProvider.LOCAL, "/home/alice/statements")
        val drive = db.directory("Drive", ImportDirectoryProvider.GDRIVE, "drive-folder-id")
        db.directory("2024", ImportDirectoryProvider.GDRIVE, "drive-subfolder-id", parent = drive)
        if (installStrategies) {
            db.installBuiltInCsvStrategies()
            db.installBuiltInApiStrategies()
        }
    } finally {
        database.close()
    }
    return location
}

private suspend fun DatabaseComponent.person(
    first: String,
    last: String?,
): PersonId = personRepository.createPerson(Person(PersonId(0), firstName = first, middleName = null, lastName = last), Source.Manual)

private suspend fun DatabaseComponent.account(name: String): AccountId =
    accountRepository.createAccount(Account(AccountId(0), name = name, openingDate = Instant.parse("2024-01-01T00:00:00Z")), Source.Manual)

// A LOCAL folder with no device belongs to another device, so it shows as not on this one.
private suspend fun DatabaseComponent.directory(
    name: String,
    provider: ImportDirectoryProvider,
    folderRef: String,
    parent: ImportDirectoryId? = null,
): ImportDirectoryId =
    importDirectoryRepository.createDirectory(
        ImportDirectory(
            id = ImportDirectoryId(Uuid.random()),
            name = name,
            provider = provider,
            folderRef = folderRef,
            topLevel = parent == null,
            parentId = parent,
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
        ),
        Source.Manual,
    )

private suspend fun DatabaseComponent.owns(
    person: PersonId,
    accounts: List<AccountId>,
) {
    accounts.forEach { personAccountOwnershipRepository.createOwnership(person, it, Source.Manual) }
}

private fun transfer(
    at: String,
    description: String,
    from: AccountId,
    to: AccountId,
    amount: String,
    asset: Asset,
) = Transfer(
    id = TransferId(0),
    timestamp = Instant.parse(at),
    description = description,
    sourceAccountId = from,
    targetAccountId = to,
    amount = Money.fromDisplayValue(amount, asset),
)
