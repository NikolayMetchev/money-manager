package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * A reversible record of an account merge: the [deletedAccountId] account was merged into
 * [survivingAccountId], moving [transferCount] transactions, and can be undone (unmerge).
 */
data class AccountMerge(
    val id: MergeId,
    val mergedAt: Instant,
    val survivingAccountId: AccountId,
    val deletedAccountId: AccountId,
    val deletedAccountName: String,
    val transferCount: Long,
    val reversed: Boolean = false,
)

/**
 * Merge context for an account that was merged away, used to label its audit trail. The merge deleted
 * the account at [deletedAccountRevisionId]; if [reversed], the undo recreated it at the next revision.
 */
data class AccountMergeContext(
    val deletedAccountRevisionId: Long,
    val survivingAccountId: AccountId,
    val survivingAccountName: String?,
    val reversed: Boolean,
)

@JvmInline
value class MergeId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

/**
 * One transfer a merge moved onto the surviving account, recording which side(s) had pointed at the
 * deleted account. Used by re-import auto-split to trace a moved transfer back to its source row.
 */
data class MergeMovedTransfer(
    val transferId: TransferId,
    val movedSource: Boolean,
    val movedTarget: Boolean,
)
