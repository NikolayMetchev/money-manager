package com.moneymanager.tui

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import kotlin.test.Test
import kotlin.test.assertContains
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

    private fun account(name: String) = Account(id = AccountId(0), name = name, openingDate = Instant.parse("2024-01-01T00:00:00Z"))
}
