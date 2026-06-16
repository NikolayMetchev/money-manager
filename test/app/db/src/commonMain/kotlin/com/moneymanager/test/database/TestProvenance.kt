package com.moneymanager.test.database

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository

/**
 * Test-only convenience: production repository create/update methods require a [Source] so it can
 * never be forgotten. Tests that don't care about provenance call the no-source overloads below,
 * which default to [Source.SampleGenerator]. Device identity is injected into the repositories, so
 * tests no longer supply one. Tests that assert a specific source call the real (source-bearing)
 * methods directly.
 */
private val TEST_SOURCE: Source = Source.SampleGenerator

/** A default [Source] for tests that don't care about provenance. */
val testSource: Source = TEST_SOURCE

suspend fun AccountRepository.createAccount(account: Account): AccountId = createAccount(account, TEST_SOURCE)

suspend fun AccountRepository.createAccountsBatch(accounts: List<Account>): List<AccountId> = createAccountsBatch(accounts) { TEST_SOURCE }

suspend fun AccountRepository.updateAccount(account: Account): Long = updateAccount(account, TEST_SOURCE)

suspend fun AccountRepository.updateAccountWithAttributes(
    account: Account?,
    accountId: AccountId,
    deletedAttributeIds: Set<Long>,
    updatedAttributes: Map<Long, NewAttribute>,
    newAttributes: List<NewAttribute>,
): Long = updateAccountWithAttributes(account, accountId, deletedAttributeIds, updatedAttributes, newAttributes, TEST_SOURCE)

suspend fun PersonRepository.createPerson(person: Person): PersonId = createPerson(person, TEST_SOURCE)

suspend fun PersonAccountOwnershipRepository.createOwnership(
    personId: PersonId,
    accountId: AccountId,
): Long = createOwnership(personId, accountId, TEST_SOURCE)

suspend fun CategoryRepository.createCategory(category: Category): Long = createCategory(category, TEST_SOURCE)

suspend fun CategoryRepository.updateCategory(category: Category): Unit = updateCategory(category, TEST_SOURCE)

suspend fun CurrencyRepository.upsertCurrencyByCode(
    code: String,
    name: String,
): CurrencyId = upsertCurrencyByCode(code, name, TEST_SOURCE)
