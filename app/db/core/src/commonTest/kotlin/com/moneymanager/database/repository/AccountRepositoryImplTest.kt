@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.createOwnership
import com.moneymanager.test.database.createPerson
import com.moneymanager.test.database.updateAccount
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class AccountRepositoryImplTest : DbTest() {
    @Test
    fun `createAccount should insert account and return generated id`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(0),
                    name = "Test Checking",
                    openingDate = now,
                )

            val accountId = repositories.accountRepository.createAccount(account)

            assertTrue(accountId.id > 0, "Generated ID should be positive")

            val retrieved = repositories.accountRepository.getAccountById(accountId).first()
            assertNotNull(retrieved)
            assertEquals(account.name, retrieved.name)
        }

    @Test
    fun `createAccount should create multiple accounts`() =
        runTest {
            val now = Clock.System.now()
            val accountNames = listOf("Checking", "Savings", "Credit Card", "Investment")

            accountNames.forEach { name ->
                val account =
                    Account(
                        id = AccountId(0),
                        name = name,
                        openingDate = now,
                    )

                val accountId = repositories.accountRepository.createAccount(account)
                val retrieved = repositories.accountRepository.getAccountById(accountId).first()

                assertNotNull(retrieved)
                assertEquals(name, retrieved.name)
            }

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            assertEquals(accountNames.size, allAccounts.size)
        }

    @Test
    fun `updateAccount should update account name`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(0),
                    name = "Original Name",
                    openingDate = now,
                )
            val accountId = repositories.accountRepository.createAccount(account)
            val retrieved = repositories.accountRepository.getAccountById(accountId).first()
            assertNotNull(retrieved)

            val updatedAccount = retrieved.copy(name = "Updated Name")
            repositories.accountRepository.updateAccount(updatedAccount)

            val updated = repositories.accountRepository.getAccountById(accountId).first()
            assertNotNull(updated)
            assertEquals("Updated Name", updated.name)
        }

    @Test
    fun `deleteAccount should remove account`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(0),
                    name = "To Delete",
                    openingDate = now,
                )
            val accountId = repositories.accountRepository.createAccount(account)

            repositories.accountRepository.deleteAccount(accountId)

            val deleted = repositories.accountRepository.getAccountById(accountId).first()
            assertEquals(null, deleted)
        }

    @Test
    fun `countTransfersByAccount should return zero for account with no transfers`() =
        runTest {
            val now = Clock.System.now()
            val accountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Empty Account", openingDate = now),
                )

            val count = repositories.accountRepository.countTransfersByAccount(accountId)

            assertEquals(0L, count)
        }

    @Test
    fun `countTransfersByAccount should return correct count`() =
        runTest {
            val now = Clock.System.now()
            val sourceAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Source", openingDate = now),
                )
            val targetAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Target", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "Transfer 1",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue("100", currency),
                ),
            )
            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "Transfer 2",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue("200", currency),
                ),
            )

            val sourceCount = repositories.accountRepository.countTransfersByAccount(sourceAccountId)
            val targetCount = repositories.accountRepository.countTransfersByAccount(targetAccountId)

            assertEquals(2L, sourceCount)
            assertEquals(2L, targetCount)
        }

    @Test
    fun `mergeAccounts should move transfers to surviving account and delete merged account`() =
        runTest {
            val now = Clock.System.now()
            val accountA =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account A", openingDate = now),
                )
            val accountB =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account B", openingDate = now),
                )
            val accountC =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account C", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            // A -> C transfer (source references account being merged away)
            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "A to C",
                    sourceAccountId = accountA,
                    targetAccountId = accountC,
                    amount = Money.fromDisplayValue("100", currency),
                ),
            )
            // C -> A transfer (target references account being merged away)
            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "C to A",
                    sourceAccountId = accountC,
                    targetAccountId = accountA,
                    amount = Money.fromDisplayValue("200", currency),
                ),
            )

            // Merge account A into account B (no transfers exist between A and B, so this is valid)
            repositories.accountRepository.mergeAccounts(
                deletedAccount = accountA,
                survivingAccount = accountB,
            )

            // Account A should be deleted
            val deletedAccount = repositories.accountRepository.getAccountById(accountA).first()
            assertEquals(null, deletedAccount)

            // Transfers should now reference account B instead of A
            val accountBTransfers = repositories.transactionRepository.getTransactionsByAccount(accountB).first()
            assertEquals(2, accountBTransfers.size)
            // A -> C became B -> C
            val movedSource = accountBTransfers.first { it.description == "A to C" }
            assertEquals(accountB, movedSource.sourceAccountId)
            assertEquals(accountC, movedSource.targetAccountId)
            // C -> A became C -> B
            val movedTarget = accountBTransfers.first { it.description == "C to A" }
            assertEquals(accountC, movedTarget.sourceAccountId)
            assertEquals(accountB, movedTarget.targetAccountId)

            // The merge is recorded as reversible
            val merges = repositories.accountRepository.getReversibleMerges().first()
            assertEquals(1, merges.size)
            assertEquals(accountB, merges.first().survivingAccountId)
            assertEquals(accountA, merges.first().deletedAccountId)
            assertEquals(2L, merges.first().transferCount)
        }

    @Test
    fun `getLatestAuditedAccountNames resolves a merged-away account's name`() =
        runTest {
            val now = Clock.System.now()
            val accountA =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account A", openingDate = now),
                )
            val accountB =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account B", openingDate = now),
                )
            val accountC =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account C", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "A to C",
                    sourceAccountId = accountA,
                    targetAccountId = accountC,
                    amount = Money.fromDisplayValue("100", currency),
                ),
            )

            repositories.accountRepository.mergeAccounts(deletedAccount = accountA, survivingAccount = accountB)

            // Account A no longer exists, but its name is still resolvable from the audit history so
            // the audit trail can label it instead of showing a bare id.
            assertEquals(null, repositories.accountRepository.getAccountById(accountA).first())
            val auditedNames = repositories.auditRepository.getLatestAuditedAccountNames()
            assertEquals("Account A", auditedNames[accountA.id])
        }

    @Test
    fun `mergeAccounts should fail when transfers exist between the two accounts`() =
        runTest {
            val now = Clock.System.now()
            val accountA =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account A", openingDate = now),
                )
            val accountB =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account B", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            // A -> B transfer: reassigning it would create an invalid self-transfer
            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "A to B",
                    sourceAccountId = accountA,
                    targetAccountId = accountB,
                    amount = Money.fromDisplayValue("100", currency),
                ),
            )

            assertFailsWith<IllegalArgumentException> {
                repositories.accountRepository.mergeAccounts(
                    deletedAccount = accountA,
                    survivingAccount = accountB,
                )
            }

            // Account A is untouched and no merge was recorded
            assertNotNull(repositories.accountRepository.getAccountById(accountA).first())
            assertEquals(
                0,
                repositories.accountRepository
                    .getReversibleMerges()
                    .first()
                    .size,
            )
        }

    @Test
    fun `unmergeAccount should recreate the account, restore owners and move transfers back`() =
        runTest {
            val now = Clock.System.now()
            val accountA =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account A", openingDate = now),
                )
            val accountB =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account B", openingDate = now),
                )
            val accountC =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account C", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            // Give account A an owner so we can assert ownership restoration.
            val personId =
                repositories.personRepository.createPerson(
                    Person(id = PersonId(0), firstName = "Owner", middleName = null, lastName = "One"),
                )
            repositories.personAccountOwnershipRepository.createOwnership(personId, accountA)

            // Give account A an attribute and then change it, so the live value ("green") differs from
            // the value the UPDATE trigger recorded ("blue"). After unmerge the attribute must come back
            // as "green": proving it is reconstructed from the cascade-delete audit row (the current
            // value at deletion), not from a stale audited OLD value.
            val colorTypeId = repositories.attributeTypeRepository.getOrCreate("color")
            val attrId = repositories.accountAttributeRepository.insert(accountA, colorTypeId, "blue")
            repositories.accountAttributeRepository.updateValue(attrId, "green")

            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "A to C",
                    sourceAccountId = accountA,
                    targetAccountId = accountC,
                    amount = Money.fromDisplayValue("100", currency),
                ),
            )
            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "C to A",
                    sourceAccountId = accountC,
                    targetAccountId = accountA,
                    amount = Money.fromDisplayValue("200", currency),
                ),
            )

            val mergeId =
                repositories.accountRepository.mergeAccounts(
                    deletedAccount = accountA,
                    survivingAccount = accountB,
                )

            repositories.accountRepository.unmergeAccount(mergeId)

            // Account A is recreated with its original id and name
            val restored = repositories.accountRepository.getAccountById(accountA).first()
            assertNotNull(restored)
            assertEquals("Account A", restored.name)

            // The undo is recorded as a NEW forward revision (one past the merge's delete revision), so
            // it shows in the audit trail as an "undo merge" step rather than colliding with the delete.
            // Account A reached revision 3 before the merge (created=1, attribute insert=2, update=3), so
            // it is restored at 4.
            assertEquals(4L, restored.revisionId)
            val accountAudit = repositories.auditRepository.getAuditHistoryForAccount(accountA)
            val restoreEntry = accountAudit.first() // newest first
            assertEquals(AuditType.INSERT, restoreEntry.auditType)
            assertEquals(4L, restoreEntry.revisionId)
            assertTrue(
                accountAudit.any { it.auditType == AuditType.DELETE },
                "the merge's delete must remain in the audit trail",
            )
            // The merge context lets the audit screen label these entries.
            val mergeContext = repositories.accountRepository.getMergesForDeletedAccount(accountA).single()
            assertTrue(mergeContext.reversed)
            assertEquals(3L, mergeContext.deletedAccountRevisionId)
            assertEquals(accountB, mergeContext.survivingAccountId)

            // The transfers reference account A again
            val accountATransfers = repositories.transactionRepository.getTransactionsByAccount(accountA).first()
            assertEquals(2, accountATransfers.size)
            val movedBackSource = accountATransfers.first { it.description == "A to C" }
            assertEquals(accountA, movedBackSource.sourceAccountId)
            val movedBackTarget = accountATransfers.first { it.description == "C to A" }
            assertEquals(accountA, movedBackTarget.targetAccountId)

            // Account B keeps only its own (zero) transfers
            assertEquals(
                0,
                repositories.transactionRepository
                    .getTransactionsByAccount(accountB)
                    .first()
                    .size,
            )

            // Ownership is restored
            val owners = repositories.personAccountOwnershipRepository.getOwnershipsByAccount(accountA).first()
            assertEquals(1, owners.size)
            assertEquals(personId, owners.first().personId)

            // Attributes are restored from the audit trail at their current (pre-merge) value.
            val restoredAttrs = repositories.accountAttributeRepository.getByAccount(accountA).first()
            assertEquals(1, restoredAttrs.size)
            assertEquals("green", restoredAttrs.single().value)

            // The merge is no longer reversible
            assertEquals(
                0,
                repositories.accountRepository
                    .getReversibleMerges()
                    .first()
                    .size,
            )

            // The surviving account's audit still records the merge — now marked as undone — even
            // though its own row was never modified by the merge/unmerge.
            val survivorMerges = repositories.accountRepository.getMergesForSurvivingAccount(accountB).first()
            assertEquals(1, survivorMerges.size)
            assertTrue(survivorMerges.single().reversed)
            assertEquals(accountA, survivorMerges.single().deletedAccountId)
            assertEquals("Account A", survivorMerges.single().deletedAccountName)
        }

    @Test
    fun `deleteAccount should still work for accounts without transfers`() =
        runTest {
            val now = Clock.System.now()
            val accountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "No Transfers", openingDate = now),
                )

            repositories.accountRepository.deleteAccount(accountId)

            val deleted = repositories.accountRepository.getAccountById(accountId).first()
            assertEquals(null, deleted)
        }
}
