package com.moneymanager.query

import com.moneymanager.domain.model.AccountId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResolveAccountTest {
    private val current = AccountWithBalances(account(1, "Current"), emptyList(), emptyList(), isShadow = false)
    private val saver = AccountWithBalances(account(2, "Current Saver"), emptyList(), emptyList(), isShadow = false)
    private val koinly = AccountWithBalances(account(3, "Koinly Wallet"), emptyList(), emptyList(), isShadow = true)
    private val snapshot = AccountSnapshot(listOf(current, saver, koinly), emptyList())

    private fun name(pattern: String) = AccountRef.Name(NameMatch.Pattern(pattern))

    @Test
    fun resolvesAnIdOrAPatternMatchingOneAccount() {
        assertEquals(saver, snapshot.resolveAccount(AccountRef.Id(AccountId(2))))
        assertEquals(saver, snapshot.resolveAccount(name("saver")))
    }

    @Test
    fun anExactNameWinsOverTheLongerNamesItIsPartOf() {
        assertEquals(current, snapshot.resolveAccount(name("current")))
    }

    @Test
    fun severalMatchesListTheCandidates() {
        val ambiguous = assertFailsWith<AmbiguousAccountException> { snapshot.resolveAccount(name("^cur")) }

        assertEquals(listOf(current.account, saver.account), ambiguous.candidates)
    }

    @Test
    fun shadowAccountsNeedIncludeShadowByNameButNotById() {
        assertFailsWith<UnknownAccountException> { snapshot.resolveAccount(name("koinly")) }
        assertEquals(koinly, snapshot.resolveAccount(name("koinly"), includeShadow = true))
        assertEquals(koinly, snapshot.resolveAccount(AccountRef.Id(AccountId(3))))
        assertFailsWith<UnknownAccountException> { snapshot.resolveAccount(AccountRef.Id(AccountId(99))) }
    }
}
