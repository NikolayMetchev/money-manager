@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountAuditEntry
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.ui.audit.FieldChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class AccountAuditDiffTest {
    private val accountId = AccountId(1)
    private val openedAt = Instant.fromEpochMilliseconds(0)

    private fun entry(
        id: Long,
        auditType: AuditType,
        revisionId: Long,
        categoryName: String?,
    ) = AccountAuditEntry(
        id = id,
        auditTimestamp = Instant.fromEpochMilliseconds(id * 1000),
        auditType = auditType,
        accountId = accountId,
        revisionId = revisionId,
        name = "Account",
        openingDate = openedAt,
        categoryId = 0,
        categoryName = categoryName,
    )

    // Regression: the newest UPDATE row stores the OLD category; its NEW value lives only on the live
    // account, so the diff must compare against the resolved current category name. Previously it did
    // not, so the most recent category change showed as Unchanged (i.e. it was invisible).
    @Test
    fun `most recent category change is detected against the live category`() {
        // Newest-first: UPDATE (stores OLD "Food") at rev 2, then the original INSERT at rev 1.
        val entries =
            listOf(
                entry(id = 2, auditType = AuditType.UPDATE, revisionId = 2, categoryName = "Food"),
                entry(id = 1, auditType = AuditType.INSERT, revisionId = 1, categoryName = "Food"),
            )
        val currentAccount = Account(id = accountId, name = "Account", openingDate = openedAt, categoryId = 9)

        val diffs =
            computeAccountAuditDiffs(
                entries = entries,
                ownershipEntries = emptyList(),
                currentAccount = currentAccount,
                currentCategoryName = "Travel",
                mergeContexts = emptyList(),
            )

        val updateDiff = diffs.single { it.auditType == AuditType.UPDATE }
        assertEquals(FieldChange.Changed("Food", "Travel"), updateDiff.categoryName)
        assertTrue(updateDiff.hasFieldChanges)
    }

    // An older category change resolves its NEW value from the next (newer) audit row, not the live one.
    @Test
    fun `older category change resolves against the following revision`() {
        // Newest-first: rev 3 keeps "Travel"; rev 2 changed Food -> Travel (stores OLD "Food"); INSERT.
        val entries =
            listOf(
                entry(id = 3, auditType = AuditType.UPDATE, revisionId = 3, categoryName = "Travel"),
                entry(id = 2, auditType = AuditType.UPDATE, revisionId = 2, categoryName = "Food"),
                entry(id = 1, auditType = AuditType.INSERT, revisionId = 1, categoryName = "Food"),
            )
        val currentAccount = Account(id = accountId, name = "Account", openingDate = openedAt, categoryId = 9)

        val diffs =
            computeAccountAuditDiffs(
                entries = entries,
                ownershipEntries = emptyList(),
                currentAccount = currentAccount,
                currentCategoryName = "Groceries",
                mergeContexts = emptyList(),
            )

        // The rev-2 entry's new value comes from the rev-3 row's stored category ("Travel").
        val rev2 = diffs.single { it.revisionId == 2L }
        assertEquals(FieldChange.Changed("Food", "Travel"), rev2.categoryName)
    }
}
