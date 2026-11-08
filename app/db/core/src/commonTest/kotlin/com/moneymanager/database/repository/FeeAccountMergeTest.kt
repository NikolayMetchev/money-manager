package com.moneymanager.database.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.importengineapi.feeAccounts
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.time.Clock

class FeeAccountMergeTest : DbTest() {
    private val now = Clock.System.now()
    private val feeAccountFor = AttributeTypeId(WellKnownIds.ACCOUNT_FEE_ACCOUNT_FOR_ATTR_TYPE_ID)

    private suspend fun account(name: String): AccountId =
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = now))

    private suspend fun feeAccountOf(account: AccountId): AccountId =
        repositories.importEngine.feeAccounts(setOf(account), Source.Manual).getValue(account)

    /** The account tagged as [owner]'s fee account, if any. */
    private suspend fun taggedFeeAccountOf(owner: AccountId): AccountId? =
        repositories.accountAttributeRepository
            .getAll()
            .first()
            .filter { it.attributeType.id == feeAccountFor && it.value == owner.id.toString() }
            .map { it.accountId }
            .also { assertEquals(true, it.size <= 1, "at most one fee account for $owner") }
            .singleOrNull()

    private suspend fun exists(account: AccountId): Boolean = repositories.accountRepository.getAccountById(account).first() != null

    @Test
    fun `an account can not have two fee accounts`() =
        runDbTest {
            val binance = account("Binance")
            feeAccountOf(binance)
            val other = account("Other")

            assertFails {
                repositories.accountAttributeRepository.insertInCreationMode(other, feeAccountFor, binance.id.toString())
            }
        }

    @Test
    fun `merging two accounts merges their fee accounts`() =
        runDbTest {
            val old = account("Old Binance")
            val binance = account("Binance")
            val oldFees = feeAccountOf(old)
            val fees = feeAccountOf(binance)

            repositories.accountRepository.mergeAccounts(deletedAccount = old, survivingAccount = binance)

            assertEquals(false, exists(oldFees), "the merged-away account's fee account was merged too")
            assertEquals(fees, taggedFeeAccountOf(binance))
            assertNull(taggedFeeAccountOf(old))
            assertEquals(fees, feeAccountOf(binance), "fees keep going to the survivor's fee account")
        }

    @Test
    fun `the survivor takes over the fee account of the merged account when it has none`() =
        runDbTest {
            val old = account("Old Binance")
            val binance = account("Binance")
            val oldFees = feeAccountOf(old)

            repositories.accountRepository.mergeAccounts(deletedAccount = old, survivingAccount = binance)

            assertEquals(oldFees, taggedFeeAccountOf(binance))
            assertEquals(oldFees, feeAccountOf(binance))
        }

    @Test
    fun `undoing a merge undoes the fee accounts merge too`() =
        runDbTest {
            val old = account("Old Binance")
            val binance = account("Binance")
            val oldFees = feeAccountOf(old)
            val fees = feeAccountOf(binance)

            val merge = repositories.accountRepository.mergeAccounts(deletedAccount = old, survivingAccount = binance)
            assertEquals(
                1,
                repositories.accountRepository
                    .getReversibleMerges()
                    .first()
                    .size,
                "the fee accounts merge is not listed",
            )
            repositories.accountRepository.unmergeAccount(merge)

            assertEquals(oldFees, taggedFeeAccountOf(old))
            assertEquals(fees, taggedFeeAccountOf(binance))
            assertEquals(emptyList(), repositories.accountRepository.getReversibleMerges().first())
        }

    @Test
    fun `undoing a merge gives a taken over fee account back`() =
        runDbTest {
            val old = account("Old Binance")
            val binance = account("Binance")
            val oldFees = feeAccountOf(old)

            val merge = repositories.accountRepository.mergeAccounts(deletedAccount = old, survivingAccount = binance)
            repositories.accountRepository.unmergeAccount(merge)

            assertEquals(oldFees, taggedFeeAccountOf(old))
            assertNull(taggedFeeAccountOf(binance))
        }

    @Test
    fun `merging two fee accounts keeps one fee account per account`() =
        runDbTest {
            val binance = account("Binance")
            val fees = feeAccountOf(binance)
            val stray = account("Stray fees")

            // A fee account merged into an account that is no fee account: the survivor becomes the fee account.
            repositories.accountRepository.mergeAccounts(deletedAccount = fees, survivingAccount = stray)
            assertEquals(stray, taggedFeeAccountOf(binance))

            val merge =
                repositories.accountRepository.mergeAccounts(
                    deletedAccount = stray,
                    survivingAccount = account("Charges"),
                )
            repositories.accountRepository.unmergeAccount(merge)
            assertEquals(stray, taggedFeeAccountOf(binance), "undone, the tag is back on the restored account")
        }
}
