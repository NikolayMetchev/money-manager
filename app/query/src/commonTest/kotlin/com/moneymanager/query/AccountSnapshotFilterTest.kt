package com.moneymanager.query

import com.moneymanager.domain.model.PersonId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountSnapshotFilterTest {
    private val current =
        AccountWithBalances(
            account = account(1, "Monzo Current"),
            owners = listOf(alice),
            balances = listOf(money("100.5", gbp), money("0", usd)),
            isShadow = false,
        )
    private val joint =
        AccountWithBalances(
            account = account(2, "Joint Savings (old)"),
            owners = listOf(alice, bob),
            balances = listOf(money("2000", gbp)),
            isShadow = false,
        )
    private val wallet =
        AccountWithBalances(
            account = account(3, "Kraken Wallet"),
            owners = listOf(bob),
            balances = listOf(money("0.25", btc), money("-3", gbp)),
            isShadow = false,
        )
    private val empty =
        AccountWithBalances(account = account(4, "Empty"), owners = emptyList(), balances = emptyList(), isShadow = false)
    private val shadow =
        AccountWithBalances(
            account = account(5, "Koinly Kraken"),
            owners = listOf(bob),
            balances = listOf(money("0.25", btc)),
            isShadow = true,
        )
    private val snapshot = AccountSnapshot(listOf(current, joint, wallet, empty, shadow), people = listOf(alice, bob))

    private fun ids(criteria: AccountQueryCriteria) = snapshot.filter(criteria).map { it.account.id.id }

    @Test
    fun noCriteriaKeepsEverythingButShadowAccounts() {
        assertEquals(listOf(current, joint, wallet, empty), snapshot.filter(AccountQueryCriteria()))
    }

    @Test
    fun includeShadowKeepsShadowAccounts() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(AccountQueryCriteria(includeShadow = true)))
    }

    @Test
    fun shadowCountAndAvailableAssetsCoverEveryAccount() {
        assertEquals(1, snapshot.shadowAccountCount)
        assertEquals(listOf("BTC", "GBP", "USD"), snapshot.availableAssets.map { it.code })
    }

    @Test
    fun substringIgnoresCaseAndTreatsRegexCharactersLiterally() {
        assertEquals(listOf(1L, 2L), ids(AccountQueryCriteria(name = NameMatch.Substring("O"))))
        assertEquals(listOf(2L), ids(AccountQueryCriteria(name = NameMatch.Substring("(OLD)"))))
        assertEquals(emptyList(), ids(AccountQueryCriteria(name = NameMatch.Substring("^Monzo"))))
    }

    @Test
    fun patternIsARegexMatchedAnywhereIgnoringCase() {
        assertEquals(listOf(1L), ids(AccountQueryCriteria(name = NameMatch.Pattern("^monzo"))))
        assertEquals(listOf(1L, 3L), ids(AccountQueryCriteria(name = NameMatch.Pattern("current|wallet"))))
        assertEquals(listOf(3L, 5L), ids(AccountQueryCriteria(name = NameMatch.Pattern("kraken"), includeShadow = true)))
    }

    @Test
    fun invalidPatternFailsWhenBuilt() {
        assertFailsWith<IllegalArgumentException> { NameMatch.Pattern("(unclosed") }
    }

    @Test
    fun ownersByIdKeepAccountsWithAnySelectedOwner() {
        assertEquals(listOf(1L, 2L), ids(AccountQueryCriteria(owners = setOf(OwnerRef.Id(alice.id)))))
        assertEquals(
            listOf(1L, 2L, 3L),
            ids(AccountQueryCriteria(owners = setOf(OwnerRef.Id(alice.id), OwnerRef.Id(bob.id)))),
        )
    }

    @Test
    fun ownersByNameMatchFullNameIgnoringCase() {
        assertEquals(listOf(1L, 2L), ids(AccountQueryCriteria(owners = setOf(OwnerRef.Name("alice smith")))))
        assertEquals(listOf(2L, 3L), ids(AccountQueryCriteria(owners = setOf(OwnerRef.Name("BOB")))))
    }

    @Test
    fun unknownOwnerNameFails() {
        val error = assertFailsWith<UnknownOwnerException> { snapshot.filter(AccountQueryCriteria(owners = setOf(OwnerRef.Name("Carol")))) }
        assertEquals("Carol", error.name)
    }

    @Test
    fun ambiguousOwnerNameFailsListingTheCandidates() {
        val otherBob = bob.copy(id = PersonId(3))
        val error =
            assertFailsWith<AmbiguousOwnerException> {
                snapshot.copy(people = listOf(alice, bob, otherBob)).filter(AccountQueryCriteria(owners = setOf(OwnerRef.Name("bob"))))
            }
        assertEquals(listOf(bob, otherBob), error.candidates)
    }

    @Test
    fun assetFilterKeepsHoldersAndListsOnlyTheSelectedAssets() {
        val result = snapshot.filter(AccountQueryCriteria(assets = setOf(AssetRef.Id(btc.id))))
        assertEquals(listOf(wallet.copy(balances = listOf(money("0.25", btc)))), result)
    }

    @Test
    fun assetCodesMatchIgnoringCase() {
        val result = snapshot.filter(AccountQueryCriteria(assets = setOf(AssetRef.Code("gbp"), AssetRef.Code("usd"))))
        assertEquals(listOf(1L, 2L, 3L), result.map { it.account.id.id })
        assertEquals(listOf(money("100.5", gbp), money("0", usd)), result.first().balances)
        assertEquals(listOf(money("-3", gbp)), result.last().balances)
    }

    @Test
    fun unknownAssetCodeMatchesNothing() {
        assertEquals(emptyList(), ids(AccountQueryCriteria(assets = setOf(AssetRef.Code("XYZ")))))
    }

    @Test
    fun nonZeroOnlyDropsZeroBalancesAndAccountsLeftWithout() {
        val result = snapshot.filter(AccountQueryCriteria(nonZeroOnly = true))
        assertEquals(listOf(1L, 2L, 3L), result.map { it.account.id.id })
        assertEquals(listOf(money("100.5", gbp)), result.first().balances)
        assertEquals(emptyList(), ids(AccountQueryCriteria(assets = setOf(AssetRef.Code("USD")), nonZeroOnly = true)))
    }

    @Test
    fun criteriaCombineWithAnd() {
        val criteria =
            AccountQueryCriteria(
                owners = setOf(OwnerRef.Name("Bob")),
                name = NameMatch.Pattern("kraken"),
                assets = setOf(AssetRef.Code("BTC")),
                includeShadow = true,
                nonZeroOnly = true,
            )
        assertEquals(listOf(3L, 5L), ids(criteria))
        assertEquals(emptyList(), ids(criteria.copy(owners = setOf(OwnerRef.Id(alice.id)))))
    }
}
