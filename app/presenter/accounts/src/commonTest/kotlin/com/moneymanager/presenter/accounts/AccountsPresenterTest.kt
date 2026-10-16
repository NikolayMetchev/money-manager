package com.moneymanager.presenter.accounts

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.query.AccountSnapshot
import com.moneymanager.query.AccountWithBalances
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class AccountsPresenterTest {
    private val gbp =
        Currency(id = CurrencyId(1), code = "GBP", name = "Pound Sterling", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val btc = CryptoAsset(id = CryptoId(1), code = "BTC", name = "Bitcoin")
    private val alice = Person(id = PersonId(1), firstName = "Alice", middleName = null, lastName = "Smith")
    private val bob = Person(id = PersonId(2), firstName = "Bob", middleName = null, lastName = null)

    private val current = entry(1, "Monzo Current", listOf(alice), listOf(money("100.5", gbp)))
    private val savings = entry(2, "Joint Savings", listOf(alice, bob), listOf(money("0", gbp)))
    private val wallet = entry(3, "Kraken Wallet", listOf(bob), listOf(money("0.25", btc)))
    private val shadow = entry(4, "Koinly Kraken", listOf(bob), listOf(money("0.25", btc)), isShadow = true)

    private val snapshots = MutableStateFlow(AccountSnapshot(listOf(current, savings, wallet, shadow), listOf(alice, bob)))

    @Test
    fun startsUnloadedThenShowsEveryRealAccount() =
        runTest {
            val pending = MutableStateFlow<AccountSnapshot?>(null)
            present(pending.filterNotNull()).test {
                assertFalse(awaitItem().loaded)
                pending.value = snapshots.value
                val model = awaitUntil { it.loaded }
                assertEquals(listOf(1L, 2L, 3L), model.displayedIds())
                assertEquals(4, model.all.size)
                assertEquals(1, model.shadowAccountCount)
            }
        }

    @Test
    fun filterEventsNarrowTheList() =
        runTest {
            present().test {
                val model = awaitUntil { it.loaded }
                model.eventSink(AccountsEvent.SetName("KRAKEN"))
                assertEquals(listOf(3L), awaitUntil { it.filter.nameText == "KRAKEN" }.displayedIds())
                model.eventSink(AccountsEvent.SetIncludeShadow(true))
                assertEquals(listOf(3L, 4L), awaitUntil { it.filter.includeShadow }.displayedIds())
                model.eventSink(AccountsEvent.SetName(""))
                model.eventSink(AccountsEvent.SetOwners(setOf(alice.id)))
                model.eventSink(AccountsEvent.SetNonZeroOnly(true))
                assertEquals(listOf(1L), awaitUntil { it.filter.nonZeroOnly }.displayedIds())
                model.eventSink(AccountsEvent.SetNonZeroOnly(false))
                model.eventSink(AccountsEvent.SetOwners(emptySet()))
                model.eventSink(AccountsEvent.SetAssets(setOf(btc.id)))
                assertEquals(listOf(3L, 4L), awaitUntil { it.filter.assetIds.isNotEmpty() && it.filter.ownerIds.isEmpty() }.displayedIds())
            }
        }

    @Test
    fun anInvalidRegexKeepsTheLastValidNameFilter() =
        runTest {
            present().test {
                val model = awaitUntil { it.loaded }
                model.eventSink(AccountsEvent.SetNameIsRegex(true))
                model.eventSink(AccountsEvent.SetName("^(Monzo|Kraken)"))
                assertEquals(listOf(1L, 3L), awaitUntil { it.filter.nameText == "^(Monzo|Kraken)" }.displayedIds())
                model.eventSink(AccountsEvent.SetName("^(Monzo"))
                val invalid = awaitUntil { it.filter.nameText == "^(Monzo" }
                assertNotNull(invalid.nameError)
                assertEquals(listOf(1L, 3L), invalid.displayedIds())
                model.eventSink(AccountsEvent.SetName("^Monzo"))
                val valid = awaitUntil { it.filter.nameText == "^Monzo" }
                assertNull(valid.nameError)
                assertEquals(listOf(1L), valid.displayedIds())
            }
        }

    @Test
    fun selectionsForVanishedOwnersAndAssetsAreDropped() =
        runTest {
            present().test {
                val model = awaitUntil { it.loaded }
                model.eventSink(AccountsEvent.SetOwners(setOf(alice.id, bob.id)))
                model.eventSink(AccountsEvent.SetAssets(setOf(gbp.id, btc.id)))
                awaitUntil { it.filter.assetIds.size == 2 }
                snapshots.value = AccountSnapshot(listOf(current), listOf(alice))
                val pruned = awaitUntil { it.filter.ownerIds == setOf(alice.id) && it.filter.assetIds == setOf<Any>(gbp.id) }
                assertEquals(listOf(1L), pruned.displayedIds())
            }
        }

    @Test
    fun updatesLiveWhenTheSnapshotChanges() =
        runTest {
            present().test {
                awaitUntil { it.loaded }
                val added = entry(5, "New Account", emptyList(), emptyList())
                snapshots.value = snapshots.value.copy(accounts = snapshots.value.accounts + added)
                assertEquals(listOf(1L, 2L, 3L, 5L), awaitUntil { it.all.size == 5 }.displayedIds())
            }
        }

    private fun present(flow: Flow<AccountSnapshot> = snapshots) = moleculeFlow(RecompositionMode.Immediate) { presentAccounts(flow) }

    private suspend fun ReceiveTurbine<AccountsModel>.awaitUntil(predicate: (AccountsModel) -> Boolean): AccountsModel {
        while (true) {
            val model = awaitItem()
            if (predicate(model)) return model
        }
    }

    private fun AccountsModel.displayedIds() = displayed.map { it.account.id.id }

    private fun entry(
        id: Long,
        name: String,
        owners: List<Person>,
        balances: List<Money>,
        isShadow: Boolean = false,
    ) = AccountWithBalances(
        account = Account(id = AccountId(id), name = name, openingDate = Instant.parse("2024-01-02T03:04:05Z")),
        owners = owners,
        balances = balances,
        isShadow = isShadow,
    )

    private fun money(
        amount: String,
        asset: Asset,
    ) = Money.fromDisplayValue(amount, asset)
}
