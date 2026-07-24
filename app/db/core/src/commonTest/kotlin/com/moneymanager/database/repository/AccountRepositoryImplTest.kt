package com.moneymanager.database.repository
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
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
    fun `getPreviousAccountNames maps a former name to the renamed account`() =
        runTest {
            val now = Clock.System.now()
            val id =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "ACME LTD", openingDate = now),
                )
            repositories.accountRepository.updateAccount(Account(id = id, name = "Acme Corp", openingDate = now))

            val previous = repositories.accountRepository.getPreviousAccountNames()

            // The old name (lowercased) resolves to the renamed, still-existing account.
            assertEquals(id, previous["acme ltd"])
        }

    @Test
    fun `getPreviousAccountNames ignores a deleted account that shared a historical name`() =
        runTest {
            val now = Clock.System.now()
            // Account A held "Shared" then was renamed away (audit row: name="Shared", account_id=A).
            val a =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Shared", openingDate = now),
                )
            repositories.accountRepository.updateAccount(Account(id = a, name = "A Renamed", openingDate = now))

            // Account B later also held "Shared" (a MORE RECENT audit row) then was deleted.
            val b =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Shared", openingDate = now),
                )
            repositories.accountRepository.updateAccount(Account(id = b, name = "B Renamed", openingDate = now))
            repositories.accountRepository.deleteAccount(b)

            // "Shared" must resolve to the still-existing A, not be dropped because B's newer row won MAX(id).
            assertEquals(a, repositories.accountRepository.getPreviousAccountNames()["shared"])
        }

    @Test
    fun `manual owner add and remove each bump the account revision and record an audit entry`() =
        runTest {
            val now = Clock.System.now()
            val person =
                repositories.personRepository.createPerson(
                    Person(id = PersonId(0), firstName = "Owner", middleName = null, lastName = "One"),
                )
            val accountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Acct", openingDate = now),
                )
            val manual = Source.Manual

            // Adding an owner is a change to the account: it must become a new revision.
            val ownershipId = repositories.personAccountOwnershipRepository.createOwnership(person, accountId, manual)
            val afterAdd = repositories.accountRepository.getAccountById(accountId).first()
            assertEquals(2L, afterAdd!!.revisionId)

            // Removing the owner is another change: another revision.
            repositories.personAccountOwnershipRepository.deleteOwnership(ownershipId)
            val afterRemove = repositories.accountRepository.getAccountById(accountId).first()
            assertEquals(3L, afterRemove!!.revisionId)

            // The account audit trail records all three: create + owner-added + owner-removed.
            val audit = repositories.auditRepository.getAuditHistoryForAccount(accountId)
            assertEquals(listOf(3L, 2L, 1L), audit.map { it.revisionId })
            assertEquals(AuditType.INSERT, audit.last().auditType)
            assertTrue(audit.take(2).all { it.auditType == AuditType.UPDATE })
        }

    @Test
    fun `import-provenance owner add does not bump the account revision`() =
        runTest {
            val now = Clock.System.now()
            val person =
                repositories.personRepository.createPerson(
                    Person(id = PersonId(0), firstName = "Owner", middleName = null, lastName = "One"),
                )
            val accountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Acct", openingDate = now),
                )

            // Default test provenance is SampleGenerator (a bulk/non-manual source): no revision bump.
            repositories.personAccountOwnershipRepository.createOwnership(person, accountId)

            val account = repositories.accountRepository.getAccountById(accountId).first()
            assertEquals(1L, account!!.revisionId)
        }

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
    fun `getLatestAuditedAccountNames resolves a merged-away account name`() =
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
    fun `mergeAccounts carries the deleted account's identity groups onto the survivor`() =
        runTest {
            val now = Clock.System.now()
            val keep =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Crypto.com Cash", openingDate = now),
                )
            val drop =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "NIKOLAY IVANOV METCHEV", openingDate = now),
                )
            val sortCode = repositories.attributeTypeRepository.getOrCreate("account-sort-code")
            val accountNumber = repositories.attributeTypeRepository.getOrCreate("account-account-number")

            repositories.accountAttributeRepository.insert(keep, sortCode, "040541", "040541|00002490")
            repositories.accountAttributeRepository.insert(keep, accountNumber, "00002490", "040541|00002490")
            repositories.accountAttributeRepository.insert(drop, sortCode, "040736", "040736|01311504")
            repositories.accountAttributeRepository.insert(drop, accountNumber, "01311504", "040736|01311504")

            repositories.accountRepository.mergeAccounts(deletedAccount = drop, survivingAccount = keep)

            // The survivor owns BOTH identities, each still a distinct group. Without the carry, the
            // cascade delete would have dropped the second one and the next import would re-create it.
            val attrs = repositories.accountAttributeRepository.getByAccount(keep).first()
            assertEquals(
                setOf("040541|00002490", "040736|01311504"),
                attrs.map { it.groupKey }.toSet(),
            )
            assertEquals(
                setOf("040541", "00002490", "040736", "01311504"),
                attrs.map { it.value }.toSet(),
            )
        }

    @Test
    fun `mergeAccounts skips an identity the survivor already holds`() =
        runTest {
            val now = Clock.System.now()
            val keep =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Keep", openingDate = now),
                )
            val drop =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Drop", openingDate = now),
                )
            val sortCode = repositories.attributeTypeRepository.getOrCreate("account-sort-code")
            val accountNumber = repositories.attributeTypeRepository.getOrCreate("account-account-number")

            // Both accounts describe the SAME real bank account, so both groups carry the same key and the
            // same contents — the merge must not duplicate them onto the survivor.
            listOf(keep, drop).forEach { account ->
                repositories.accountAttributeRepository.insert(account, sortCode, "040541", "040541|00002490")
                repositories.accountAttributeRepository.insert(account, accountNumber, "00002490", "040541|00002490")
            }

            repositories.accountRepository.mergeAccounts(deletedAccount = drop, survivingAccount = keep)

            val attrs = repositories.accountAttributeRepository.getByAccount(keep).first()
            assertEquals(2, attrs.size)
        }

    @Test
    fun `mergeAccounts keeps both values when a group key collides with differing contents`() =
        runTest {
            val now = Clock.System.now()
            val keep =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Keep", openingDate = now),
                )
            val drop =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Drop", openingDate = now),
                )
            val sortCode = repositories.attributeTypeRepository.getOrCreate("account-sort-code")
            val accountNumber = repositories.attributeTypeRepository.getOrCreate("account-account-number")

            // A stale group key: both accounts use the key "legacy" for different real identities (someone
            // hand-edited a sort code, so the key no longer matches its contents). Neither may be lost.
            repositories.accountAttributeRepository.insert(keep, sortCode, "040541", "legacy")
            repositories.accountAttributeRepository.insert(keep, accountNumber, "00002490", "legacy")
            repositories.accountAttributeRepository.insert(drop, sortCode, "040736", "legacy")
            repositories.accountAttributeRepository.insert(drop, accountNumber, "01311504", "legacy")

            repositories.accountRepository.mergeAccounts(deletedAccount = drop, survivingAccount = keep)

            val attrs = repositories.accountAttributeRepository.getByAccount(keep).first()
            assertEquals(4, attrs.size)
            assertEquals(setOf("040541", "00002490", "040736", "01311504"), attrs.map { it.value }.toSet())
            // The clashing group was re-keyed rather than overwritten, so the two identities stay separable.
            assertEquals(2, attrs.map { it.groupKey }.toSet().size)
        }

    @Test
    fun `unmergeAccount should recreate the account then restore owners and move transfers back`() =
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

            // Merge/unmerge source rows are recorded against the injected device, so they carry
            // resolvable device info (asserted below).
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

            // Account A reached revision 3 before the merge (created=1, attribute insert=2, update=3).
            // The merge's delete is its own forward revision (4), and the undo is the next one (5), so the
            // trail reads created → … → deleted → restored rather than collapsing the delete into the
            // prior change's revision.
            assertEquals(5L, restored.revisionId)
            val accountAudit = repositories.auditRepository.getAuditHistoryForAccount(accountA)
            val restoreEntry = accountAudit.first() // newest first
            assertEquals(AuditType.INSERT, restoreEntry.auditType)
            assertEquals(5L, restoreEntry.revisionId)
            // The recreated account is sourced as a merge-undo (not "source data missing").
            assertEquals(Source.Unmerge, restoreEntry.source?.source)
            // The delete is recorded at its own revision (4), distinct from the prior change at revision 3,
            // and is sourced as a merge (not "source data missing").
            val accountDeleteEntry = accountAudit.single { it.auditType == AuditType.DELETE }
            assertEquals(4L, accountDeleteEntry.revisionId)
            assertEquals(Source.Merge, accountDeleteEntry.source?.source)
            // The merge context lets the audit screen label these entries.
            val mergeContext = repositories.accountRepository.getMergesForDeletedAccount(accountA).single()
            assertTrue(mergeContext.reversed)
            assertEquals(4L, mergeContext.deletedAccountRevisionId)
            assertEquals(accountB, mergeContext.survivingAccountId)

            // The transfers reference account A again
            val accountATransfers = repositories.transactionRepository.getTransactionsByAccount(accountA).first()
            assertEquals(2, accountATransfers.size)
            val movedBackSource = accountATransfers.first { it.description == "A to C" }
            assertEquals(accountA, movedBackSource.sourceAccountId)
            val movedBackTarget = accountATransfers.first { it.description == "C to A" }
            assertEquals(accountA, movedBackTarget.targetAccountId)

            // The reassignment bumps each moved transfer's revision; those revisions are sourced too — a
            // MERGE when the merge moved it out and a MERGE_UNDO when the undo moved it back — rather than
            // leaving the transaction audit trail with "source data missing".
            val transferSources =
                repositories.auditRepository
                    .getAuditHistoryForTransfer(movedBackSource.id)
                    .mapNotNull { it.source }
            val transferSourceOrigins = transferSources.map { it.source }
            assertTrue(Source.Merge in transferSourceOrigins, "the merge reassignment must be sourced")
            assertTrue(Source.Unmerge in transferSourceOrigins, "the unmerge reassignment must be sourced")
            // Those sources also carry the acting device (not just a bare source type).
            val mergeSource = transferSources.first { it.source == Source.Merge }
            assertNotNull(mergeSource.deviceInfo)

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

            // The cascade-delete audit row carries the EXACT revision the attribute's value became
            // effective at (3 = create→1, insert blue→2, update green→3), proving the attribute's
            // stamped revision_id is used as the fallback rather than a guessed value.
            val attrAudit = repositories.auditRepository.getAttributeAuditByAccount(accountA)
            val deleteEntry = attrAudit.single { it.auditType == AuditType.DELETE }
            assertEquals("green", deleteEntry.value)
            assertEquals(3L, deleteEntry.revisionId)

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
