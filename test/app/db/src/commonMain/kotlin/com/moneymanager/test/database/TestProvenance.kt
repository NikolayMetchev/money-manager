package com.moneymanager.test.database

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.CategoryWriteRepository
import com.moneymanager.domain.repository.CurrencyWriteRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.PersonWriteRepository

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

suspend fun AccountWriteRepository.createAccount(account: Account): AccountId = createAccount(account, TEST_SOURCE)

suspend fun AccountWriteRepository.createAccountsBatch(accounts: List<Account>): List<AccountId> =
    createAccountsBatch(accounts) {
        TEST_SOURCE
    }

suspend fun AccountWriteRepository.updateAccount(account: Account): Long = updateAccount(account, TEST_SOURCE)

suspend fun AccountWriteRepository.updateAccountWithAttributes(
    account: Account?,
    accountId: AccountId,
    deletedAttributeIds: Set<Long>,
    updatedAttributes: Map<Long, NewAttribute>,
    newAttributes: List<NewAttribute>,
): Long = updateAccountWithAttributes(account, accountId, deletedAttributeIds, updatedAttributes, newAttributes, TEST_SOURCE)

suspend fun PersonWriteRepository.createPerson(person: Person): PersonId = createPerson(person, TEST_SOURCE)

suspend fun PersonAccountOwnershipWriteRepository.createOwnership(
    personId: PersonId,
    accountId: AccountId,
): Long = createOwnership(personId, accountId, TEST_SOURCE)

suspend fun CategoryWriteRepository.createCategory(category: Category): Long = createCategory(category, TEST_SOURCE)

suspend fun CategoryWriteRepository.updateCategory(category: Category): Unit = updateCategory(category, TEST_SOURCE)

suspend fun CurrencyWriteRepository.upsertCurrencyByCode(
    code: String,
    name: String,
): CurrencyId = upsertCurrencyByCode(code, name, TEST_SOURCE)
