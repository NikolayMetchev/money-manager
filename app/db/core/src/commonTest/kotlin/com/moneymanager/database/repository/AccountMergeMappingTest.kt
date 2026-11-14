package com.moneymanager.database.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.regex.compileRegex
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/** A merge that maps the deleted account's name sends later imports of that name to the survivor, until it is undone. */
class AccountMergeMappingTest : DbTest() {
    private suspend fun account(name: String): AccountId =
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = Clock.System.now()))

    @Test
    fun `merge with mapDeletedName adds a global mapping of the exact deleted name to the survivor`() =
        runDbTest {
            val keep = account("Keep")
            val drop = account("Drop (old) .*")

            repositories.accountRepository.mergeAccounts(deletedAccount = drop, survivingAccount = keep, mapDeletedName = true)

            val mapping =
                repositories.accountMappingRepository
                    .getAllMappings()
                    .first()
                    .single()
            assertEquals(null, mapping.strategyId)
            assertEquals(keep, mapping.accountId)
            assertTrue(mapping.valuePattern.containsMatchIn("Drop (old) .*"))
            assertFalse(mapping.valuePattern.containsMatchIn("Drop (old) x"))
            assertFalse(mapping.valuePattern.containsMatchIn("Drop (old) .* 2"))
        }

    @Test
    fun `merge without mapDeletedName adds no mapping`() =
        runDbTest {
            val keep = account("Keep")
            val drop = account("Drop")

            repositories.accountRepository.mergeAccounts(deletedAccount = drop, survivingAccount = keep)

            assertTrue(
                repositories.accountMappingRepository
                    .getAllMappings()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `undoing the merge removes the mapping it added`() =
        runDbTest {
            val keep = account("Keep")
            val drop = account("Drop")

            val merge = repositories.accountRepository.mergeAccounts(deletedAccount = drop, survivingAccount = keep, mapDeletedName = true)
            repositories.accountRepository.unmergeAccount(merge)

            assertTrue(
                repositories.accountMappingRepository
                    .getAllMappings()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `an existing global mapping of the same pattern is kept and survives the undo`() =
        runDbTest {
            val keep = account("Keep")
            val other = account("Other")
            val drop = account("Drop")
            val existing = repositories.accountMappingRepository.createMapping(compileRegex("^\\QDrop\\E$"), other)

            val merge = repositories.accountRepository.mergeAccounts(deletedAccount = drop, survivingAccount = keep, mapDeletedName = true)
            assertEquals(
                other,
                repositories.accountMappingRepository
                    .getAllMappings()
                    .first()
                    .single()
                    .accountId,
            )

            repositories.accountRepository.unmergeAccount(merge)
            assertEquals(
                existing,
                repositories.accountMappingRepository
                    .getAllMappings()
                    .first()
                    .single()
                    .id,
            )
        }

    @Test
    fun `a mapping that pointed at the deleted account is replaced by one to the survivor`() =
        runDbTest {
            val keep = account("Keep")
            val drop = account("Drop")
            repositories.accountMappingRepository.createMapping(compileRegex("^\\QDrop\\E$"), drop)

            repositories.accountRepository.mergeAccounts(deletedAccount = drop, survivingAccount = keep, mapDeletedName = true)

            assertEquals(
                keep,
                repositories.accountMappingRepository
                    .getAllMappings()
                    .first()
                    .single()
                    .accountId,
            )
        }
}
