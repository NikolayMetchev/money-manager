package com.moneymanager.tools.benchdb

import com.moneymanager.database.JvmDatabaseManager
import com.moneymanager.database.di.createImportEngine
import com.moneymanager.database.di.openDatabaseComponent
import com.moneymanager.database.di.toAppServices
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportSettings
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.createCurrency
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The account the startup benchmark opens: it takes part in most transfers, so its first page is a real query. */
const val BENCH_ACCOUNT = "Alex Current Account"

private const val SEED = 1254
private const val TRANSFERS = 20_000
private const val BATCH = 1_000
private const val MINUTES_PER_TRANSFER = 131 // ~5 years over 20,000 transfers
private val START = Instant.parse("2021-01-01T08:00:00Z")

private class Own(
    val name: String,
    val owner: Int,
    val asset: String,
)

private val PEOPLE = listOf("Alex" to "Morgan", "Sam" to "Morgan", "Jo" to "Rivers")

private val OWN =
    listOf(
        Own(BENCH_ACCOUNT, 0, "GBP"),
        Own("Alex Savings", 0, "GBP"),
        Own("Alex Credit Card", 0, "GBP"),
        Own("Alex USD Wallet", 0, "USD"),
        Own("Alex Bitcoin", 0, "BTC"),
        Own("Sam Current Account", 1, "GBP"),
        Own("Sam Savings", 1, "GBP"),
        Own("Sam Credit Card", 1, "GBP"),
        Own("Jo Current Account", 2, "GBP"),
        Own("Jo ISA", 2, "GBP"),
        Own("Jo EUR Account", 2, "EUR"),
        Own("Joint Account", 0, "GBP"),
    )

private val MERCHANTS =
    listOf(
        "Grocer",
        "Bakery",
        "Coffee Shop",
        "Petrol Station",
        "Rail Company",
        "Bus Company",
        "Airline",
        "Hotel",
        "Electricity Supplier",
        "Gas Supplier",
        "Water Company",
        "Council Tax",
        "Broadband",
        "Mobile Network",
        "Streaming Service",
        "Music Service",
        "Gym",
        "Pharmacy",
        "Bookshop",
        "Hardware Store",
        "Clothes Shop",
        "Restaurant",
        "Takeaway",
        "Cinema",
        "Theatre",
        "Insurance",
        "Landlord",
        "Employer",
        "Dentist",
        "Vet",
        "Garage",
        "Charity",
        "Online Marketplace",
        "Furniture Store",
        "Garden Centre",
        "Crypto Exchange",
        "US Merchant",
        "EU Merchant",
    )

/**
 * Writes the startup benchmark's database (#1254) to the path given: three people, 50 accounts and 20,000
 * transfers over five years in GBP, USD, EUR and BTC, with the setup wizard done. It's deterministic in
 * content, written through the import engine like any import, and any front end (the desktop app, `mm-jvm`,
 * native `mm`) opens it. `--empty` writes the same database with no people, accounts or transfers.
 */
fun main(args: Array<String>) {
    val empty = "--empty" in args
    val output = File(requireNotNull(args.singleOrNull { it != "--empty" }) { "usage: bench-db [--empty] <output.db>" }).absoluteFile
    for (suffix in listOf("", "-wal", "-shm")) File(output.path + suffix).delete()
    runBlocking {
        val database = JvmDatabaseManager().openDatabase(DbLocation(output.path))
        val component = openDatabaseComponent(database)
        val engine = component.createImportEngine(EditGate.AlwaysWritable)
        val services = component.toAppServices(engine)
        engine.createCurrency("GBP", "Pound Sterling", 2, Source.SampleGenerator)
        engine.createCurrency("USD", "US Dollar", 2, Source.SampleGenerator)
        engine.createCurrency("EUR", "Euro", 2, Source.SampleGenerator)
        engine.createCrypto("BTC", "Bitcoin", Source.SampleGenerator)
        val currencies = services.accounts.currencyRepository
        val gbp = checkNotNull(currencies.getCurrencyByCode("GBP").first())
        val assets: Map<String, Asset> =
            listOf("USD", "EUR").associateWith { checkNotNull(currencies.getCurrencyByCode(it).first()) { it } } +
                ("GBP" to gbp) + (
                    "BTC" to
                        checkNotNull(
                            services.accounts.cryptoRepository
                                .getCryptoAssetByCode("BTC")
                                .first(),
                        )
                )
        if (!empty) writeTransfers(engine, createAccountsAndOwners(engine), assets)
        engine.import(
            ImportBatch(
                settings = ImportSettings(defaultCurrencyId = gbp.id, setupWizardCompleted = true),
            ),
        )
        database.close()
    }
}

private suspend fun createAccountsAndOwners(engine: ImportEngine): Map<String, AccountId> {
    val names = OWN.map { it.name } + MERCHANTS
    val accountKeys = names.associateWith { LocalAccountKey(it) }
    val personKeys = PEOPLE.indices.map { LocalPersonKey("person-$it") }
    val result =
        engine.import(
            ImportBatch(
                accountsToCreate =
                    names.map { name ->
                        ImportAccountIntent(
                            key = accountKeys.getValue(name),
                            source = Source.SampleGenerator,
                            match = AccountMatchKey.ByName(name),
                            name = name,
                            openingDate = START,
                        )
                    },
                peopleToCreate =
                    PEOPLE.mapIndexed { index, (first, last) ->
                        ImportPersonIntent(
                            key = personKeys[index],
                            source = Source.SampleGenerator,
                            match = PersonMatchKey.ByNameKey("$first $last".lowercase()),
                            firstName = first,
                            lastName = last,
                        )
                    },
                ownerships =
                    OWN.map { own ->
                        ImportOwnershipIntent(
                            source = Source.SampleGenerator,
                            personKey = personKeys[own.owner],
                            account = AccountRef.Local(accountKeys.getValue(own.name)),
                        )
                    },
            ),
        )
    return names.associateWith { result.createdAccountIds.getValue(accountKeys.getValue(it)) }
}

private suspend fun writeTransfers(
    engine: ImportEngine,
    accounts: Map<String, AccountId>,
    assets: Map<String, Asset>,
) {
    val random = Random(SEED)
    val gbpOwn = OWN.filter { it.asset == "GBP" && it.name != BENCH_ACCOUNT }
    val domesticMerchants =
        MERCHANTS.filterNot {
            it == "Employer" || it.startsWith("US ") || it.startsWith("EU ") ||
                it == "Crypto Exchange"
        }
    val transfers =
        (0 until TRANSFERS).map { index ->
            val timestamp = START + (index * MINUTES_PER_TRANSFER).minutes
            val roll = random.nextInt(100)
            val (from, to, assetCode) =
                when {
                    // Monthly-ish salary into the benchmark account.
                    index % 400 == 0 -> Triple("Employer", BENCH_ACCOUNT, "GBP")

                    roll < 45 -> Triple(BENCH_ACCOUNT, domesticMerchants.random(random), "GBP")

                    roll < 50 -> Triple(BENCH_ACCOUNT, gbpOwn.random(random).name, "GBP")

                    roll < 85 -> Triple(gbpOwn.random(random).name, domesticMerchants.random(random), "GBP")

                    roll < 92 -> Triple("Alex USD Wallet", "US Merchant", "USD")

                    roll < 97 -> Triple("Jo EUR Account", "EU Merchant", "EUR")

                    else -> Triple("Crypto Exchange", "Alex Bitcoin", "BTC")
                }
            val display =
                when {
                    from == "Employer" -> "3250.00"
                    assetCode == "BTC" -> "0.00" + (100_000 + random.nextInt(900_000)).toString()
                    else -> "${1 + random.nextInt(250)}.${(random.nextInt(100)).toString().padStart(2, '0')}"
                }
            ImportTransfer(
                source = Source.SampleGenerator,
                fromAccount = AccountRef.Existing(accounts.getValue(from)),
                toAccount = AccountRef.Existing(accounts.getValue(to)),
                timestamp = timestamp,
                description = if (from == "Employer") "Salary" else "$to payment",
                amount = Money.fromDisplayValue(display, assets.getValue(assetCode)),
            )
        }
    for (chunk in transfers.chunked(BATCH)) engine.import(ImportBatch(transfers = chunk))
}
