package com.moneymanager.tui

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.createCurrency
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class AccountsScreenTest {
    @Test
    fun listsAccountsLiveAndFiltersThemThroughTheSharedPresenter() {
        val location = createTestDatabaseLocation()
        try {
            tui(location, listOf(DatabaseScreen, AccountsScreen)) { _, controller ->
                screen { it.contains("Press o to open") }
                press("2")
                screen { it.contains("Accounts · 0 of 0") }

                // Created after the tab opened: the list follows the database.
                val engine = checkNotNull(controller.session).importEngine
                engine.createAccount(account("Monzo Current"), Source.Manual)
                engine.createAccount(account("Kraken Wallet"), Source.Manual)
                engine.createAccount(account("Koinly Kraken"), Source.Manual, shadowSource = "Koinly")
                val all = screen { it.contains("Accounts · 2 of 3") }
                assertContains(all, "Monzo Current")
                assertFalse(all.contains("Koinly Kraken"))

                press("/")
                type("kraken")
                press("Enter")
                val filtered = screen { it.contains("Accounts · 1 of 3") }
                assertContains(filtered, "Kraken Wallet")
                assertFalse(filtered.contains("Monzo Current"))

                press("s")
                assertContains(screen { it.contains("Accounts · 2 of 3") }, "Koinly Kraken (shadow)")

                // While the filter is edited the shell's keys are text: q doesn't quit, it filters.
                press("/")
                type("q")
                screen { it.contains("Accounts · 0 of 3") }
                press("Escape")

                // An invalid regex keeps the last valid one's matches.
                press("r")
                press("/")
                press("Backspace")
                type("(")
                assertContains(screen { it.contains("Regex:") && it.contains("kraken(") }, "Accounts · 2 of 3")
            }
        } finally {
            deleteTestDatabase(location)
        }
    }

    @Test
    fun picksOwnersAndAssetsAndGroupsByOwner() {
        val location = createTestDatabaseLocation()
        try {
            tui(location, listOf(DatabaseScreen, AccountsScreen)) { _, controller ->
                screen { it.contains("Press o to open") }
                val engine = checkNotNull(controller.session).importEngine
                val gbp =
                    Currency(
                        id = engine.createCurrency("GBP", "Pound Sterling"),
                        code = "GBP",
                        name = "Pound Sterling",
                        scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR,
                    )
                val btc = CryptoAsset(id = engine.createCrypto("BTC", "Bitcoin"), code = "BTC", name = "Bitcoin")
                engine.import(
                    ImportBatch(
                        accountsToCreate = listOf("Monzo Current", "Joint Savings", "Kraken Wallet", "Cash").map { newAccount(it) },
                        peopleToCreate = listOf(person("Alice", "Smith"), person("Bob", "Jones")),
                        ownerships =
                            listOf(
                                owns("Alice", "Monzo Current"),
                                owns("Alice", "Joint Savings"),
                                owns("Bob", "Joint Savings"),
                                owns("Bob", "Kraken Wallet"),
                            ),
                        transfers =
                            listOf(
                                transfer(0, "Monzo Current", Money.fromDisplayValue("100", gbp)),
                                transfer(1, "Joint Savings", Money.fromDisplayValue("5", gbp)),
                                transfer(2, "Kraken Wallet", Money.fromDisplayValue("0.5", btc)),
                            ),
                    ),
                )
                // Balances come from the materialized views, which an import run refreshes once it's done.
                checkNotNull(controller.session)
                    .services.imports.maintenance
                    .refreshMaterializedViews()
                press("2")
                screen { it.contains("Accounts · 4 of 4") && it.contains("BTC") }

                // Owners: typing narrows the picker, Space ticks, Enter applies. q is filter text, not quit.
                press("p")
                screen { it.contains("Owners · any") }
                type("q")
                screen { it.contains("Nothing matches.") }
                press("Backspace")
                type("bob")
                press(" ")
                screen { it.contains("Owners · 1 picked") }
                press("Enter")
                val bobs = screen { it.contains("Accounts · 2 of 4") }
                assertContains(bobs, "Owners: Bob Jones")
                assertContains(bobs, "Joint Savings")
                assertContains(bobs, "Kraken Wallet")
                assertFalse(bobs.contains("Monzo Current"))

                // Ctrl-A in the picker goes back to any owner.
                press("p")
                pressCtrl('a')
                press("Enter")
                screen { it.contains("Accounts · 4 of 4") && it.contains("Owners: any") }

                press("a")
                press("ArrowDown") // BTC, GBP: Space ticks the one under the cursor.
                press("ArrowUp")
                press(" ")
                press("Enter")
                val btcHolders = screen { it.contains("Accounts · 2 of 4") }
                assertContains(btcHolders, "Assets: BTC")
                assertContains(btcHolders, "Kraken Wallet")
                assertContains(btcHolders, "Cash")
                assertFalse(btcHolders.contains("Joint Savings"))
                press("a")
                pressCtrl('a')
                press("Enter")
                screen { it.contains("Accounts · 4 of 4") }

                // Grouped: the joint account is under both owners, and the unowned ones come last.
                press("g")
                val grouped = screen { it.contains("grouped by owner") }
                val lines = grouped.lines().map { it.trim() }
                val headings = lines.filter { it.matches(Regex("""^(Alice Smith|Bob Jones|No owner) · \d+$""")) }
                assertEquals(listOf("Alice Smith · 2", "Bob Jones · 2", "No owner · 1"), headings)
                assertEquals(2, lines.count { it.startsWith("Joint Savings") })
            }
        } finally {
            deleteTestDatabase(location)
        }
    }

    private fun newAccount(name: String) =
        ImportAccountIntent(
            key = LocalAccountKey(name),
            source = Source.Manual,
            name = name,
            openingDate = Instant.parse("2024-01-01T00:00:00Z"),
        )

    private fun person(
        first: String,
        last: String,
    ) = ImportPersonIntent(
        key = LocalPersonKey(first),
        source = Source.Manual,
        match = PersonMatchKey.ByNameKey("$first $last".lowercase()),
        firstName = first,
        lastName = last,
    )

    private fun owns(
        person: String,
        account: String,
    ) = ImportOwnershipIntent(
        source = Source.Manual,
        personKey = LocalPersonKey(person),
        account = AccountRef.Local(LocalAccountKey(account)),
    )

    private fun transfer(
        index: Long,
        to: String,
        amount: Money,
    ) = ImportTransfer(
        source = Source.Manual,
        rowKey = ImportRowKey.Manual(index),
        fromAccount = AccountRef.Local(LocalAccountKey("Cash")),
        toAccount = AccountRef.Local(LocalAccountKey(to)),
        timestamp = Instant.parse("2024-02-01T00:00:00Z"),
        description = "Top up $to",
        amount = amount,
    )

    private fun account(name: String) = Account(id = AccountId(0), name = name, openingDate = Instant.parse("2024-01-01T00:00:00Z"))
}
