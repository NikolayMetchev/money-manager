package com.moneymanager.test.database

import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityProvenance
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository

/**
 * Test-only convenience: production repository create/update methods require an [EntityProvenance]
 * so it can never be forgotten. Tests that don't care about provenance call the no-provenance
 * overloads below, which default to sample-generator provenance against the seeded SYSTEM device
 * (id 1 — inserted first by `seedDatabase`), a valid `device(id)` FK target in every seeded test DB.
 *
 * Tests that assert a specific source must call the real (provenance-bearing) methods directly.
 */
private val TEST_PROVENANCE: EntityProvenance = EntityProvenance.SampleGenerator(DeviceId(1))

/** Sample-generator provenance for the given [deviceId]. */
fun testProvenance(deviceId: DeviceId): EntityProvenance = EntityProvenance.SampleGenerator(deviceId)

/** A default [EntityProvenance] for the component's device. */
val DatabaseComponent.testProvenance: EntityProvenance
    get() = EntityProvenance.SampleGenerator(deviceId)

suspend fun AccountRepository.createAccount(account: Account): AccountId = createAccount(account, TEST_PROVENANCE)

suspend fun AccountRepository.createAccountsBatch(accounts: List<Account>): List<AccountId> =
    createAccountsBatch(accounts) { TEST_PROVENANCE }

suspend fun AccountRepository.updateAccount(account: Account): Long = updateAccount(account, TEST_PROVENANCE)

suspend fun AccountRepository.updateAccountWithAttributes(
    account: Account?,
    accountId: AccountId,
    deletedAttributeIds: Set<Long>,
    updatedAttributes: Map<Long, NewAttribute>,
    newAttributes: List<NewAttribute>,
): Long = updateAccountWithAttributes(account, accountId, deletedAttributeIds, updatedAttributes, newAttributes, TEST_PROVENANCE)

suspend fun PersonRepository.createPerson(person: Person): PersonId = createPerson(person, TEST_PROVENANCE)

suspend fun PersonAccountOwnershipRepository.createOwnership(
    personId: PersonId,
    accountId: AccountId,
): Long = createOwnership(personId, accountId, TEST_PROVENANCE)

suspend fun CategoryRepository.createCategory(category: Category): Long = createCategory(category, TEST_PROVENANCE)

suspend fun CurrencyRepository.upsertCurrencyByCode(
    code: String,
    name: String,
): CurrencyId = upsertCurrencyByCode(code, name, TEST_PROVENANCE)
