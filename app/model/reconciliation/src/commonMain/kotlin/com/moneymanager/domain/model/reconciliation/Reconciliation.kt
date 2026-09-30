package com.moneymanager.domain.model.reconciliation

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import kotlin.time.Instant

/**
 * An account holding data imported from a reconciliation source (it carries the
 * `reconciliation-source` attribute; see `WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID`).
 */
data class ShadowAccount(
    val accountId: AccountId,
    val name: String,
    val sourceName: String,
    /** The user removed this wallet's last link, so it is never linked automatically again. */
    val autoLinkDeclined: Boolean = false,
)

/** The user's statement that [shadowAccountId] mirrors the real account [realAccountId]. */
data class ReconciliationLink(
    val shadowAccountId: AccountId,
    val realAccountId: AccountId,
)

/**
 * A reconciliation source as configured by CSV strategies (see `ReconciliationConfig`): every
 * strategy sharing [name] feeds the same shadow accounts.
 */
data class ReconciliationSource(
    val name: String,
    val strategyNames: List<String>,
    /** See `ReconciliationConfig.linkableAccountPrefix`; the first non-null among the source's strategies. */
    val linkableAccountPrefix: String?,
) {
    /** Whether [account] is one of this source's wallets, i.e. mirrors a real account and needs a link. */
    fun isLinkable(account: ShadowAccount): Boolean =
        account.sourceName == name && (linkableAccountPrefix == null || account.name.startsWith(linkableAccountPrefix))

    /** [account]'s name without [linkableAccountPrefix]: the name its real counterpart should carry. */
    fun walletName(account: ShadowAccount): String = linkableAccountPrefix?.let(account.name::removePrefix)?.trim() ?: account.name
}

enum class LegTransactionKind { TRANSFER, TRADE }

/**
 * One account-side of a transaction: a transfer has two legs (source outflow, target inflow), a trade
 * two (from-asset outflow, to-asset inflow — both usually on one account).
 *
 * @property amount signed: negative for an outflow from [accountId], positive for an inflow
 * @property counterpartyAccountId the transfer's other account; null for trade legs
 */
data class ReconciliationLeg(
    val transactionId: Long,
    val kind: LegTransactionKind,
    val timestamp: Instant,
    val description: String,
    val accountId: AccountId,
    val counterpartyAccountId: AccountId?,
    val amount: Money,
    val isExcluded: Boolean,
    /**
     * Shared by the legs of one movement (e.g. a trade and the fee its source booked as a separate
     * transfer), so they can be summed against the other side's single gross leg without pulling in
     * unrelated movements that happen to share a second.
     */
    val movementKey: String = "$kind:$transactionId",
)
