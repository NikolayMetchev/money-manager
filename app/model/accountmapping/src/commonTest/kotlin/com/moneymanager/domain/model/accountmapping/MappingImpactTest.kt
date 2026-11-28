package com.moneymanager.domain.model.accountmapping

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.regex.compileRegex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MappingImpactTest {
    private val opened = Instant.fromEpochSeconds(0)

    private fun account(
        id: Long,
        name: String,
    ) = Account(id = AccountId(id), name = name, openingDate = opened)

    private val target = account(1, "Tesco")
    private val store = account(2, "TESCO STORES 3297")
    private val express = account(3, "tesco express")
    private val renamed = account(4, "Groceries")
    private val unrelated = account(5, "Sainsbury's")
    private val shadow = account(6, "Koinly: Tesco wallet")
    private val accounts = listOf(target, store, express, renamed, unrelated, shadow)
    private val pattern = compileRegex("tesco", RegexOption.IGNORE_CASE)

    private fun captured(
        targetId: AccountId = target.id,
        previousNames: Map<String, AccountId> = emptyMap(),
    ) = accountsCapturedByMapping(pattern, targetId, accounts, previousNames, shadowAccountIds = setOf(shadow.id)).map { it.name }

    @Test
    fun `captures the accounts whose name the pattern matches but never the target or a shadow account`() {
        assertEquals(listOf("tesco express", "TESCO STORES 3297"), captured())
    }

    @Test
    fun `captures an account by an earlier name`() {
        assertEquals(
            listOf("Groceries", "tesco express", "TESCO STORES 3297"),
            captured(previousNames = mapOf("TESCO METRO" to renamed.id)),
        )
    }

    @Test
    fun `a shadow target only captures shadow accounts`() {
        val otherShadow = account(7, "Koinly: TESCO card")
        val captured =
            accountsCapturedByMapping(pattern, shadow.id, accounts + otherShadow, emptyMap(), setOf(shadow.id, otherShadow.id))
        assertEquals(listOf(otherShadow), captured)
    }

    @Test
    fun `an empty pattern captures nothing`() {
        assertEquals(emptyList(), accountsCapturedByMapping(compileRegex(""), target.id, accounts, emptyMap(), emptySet()))
    }
}
