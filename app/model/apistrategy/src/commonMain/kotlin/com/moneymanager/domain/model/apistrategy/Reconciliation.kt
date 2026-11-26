package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.rules.SettlementWindow
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** A bridge to another (already-imported) account this strategy's transfers should reconcile against. */
@Serializable
data class ApiAccountBridge(
    /** Display name of the other owned account (e.g. the CSV "Crypto.com" App account). */
    val otherAccountName: String,
)

/**
 * How far apart this feed and another source may record one movement and still count it once. Every API strategy
 * reconciles against other sources; these only say how close the records must be.
 *
 * @property crossSource Both records name the same pair of accounts, so they agree to the minute: a Faster Payment
 *                       is near-instant, and this absorbs send-versus-settle differences between banks. Matching
 *                       only other sources' records, it never collapses this provider's own repeat transfers.
 * @property placeholder The other record names only one end (a statement's "Deposit", a card's "PAYMENT
 *                       RECEIVED") and has just the account, direction and amount to go on, while the two
 *                       sources stamp the movement up to a day or more apart, so it is much wider.
 */
@Serializable
data class ApiReconcileWindows(
    val crossSource: SettlementWindow = SettlementWindow.Elapsed(5.minutes.inWholeSeconds),
    val placeholder: SettlementWindow = SettlementWindow.Elapsed(3.days.inWholeSeconds),
)

/**
 * Reconciles internal transfers between this strategy's account and another owned account that records
 * the same real movement at its own end (e.g. money moved from the Crypto.com App into the Exchange:
 * the App CSV records a withdrawal out, the Exchange API records a deposit in). On a match within
 * [window] of the same asset and (within [amountTolerancePercent]) amount, the two half-transfers
 * are collapsed into one internal transfer and linked via the `reconciled` relationship.
 */
@Serializable
data class ApiInternalTransferReconcile(
    @Serializable(with = SortedAccountBridgeListSerializer::class)
    val bridges: List<ApiAccountBridge>,
    val window: SettlementWindow,
    /**
     * Allowed amount difference as a percentage, held as a decimal string (e.g. "2" or "0.5") so it is
     * parsed to `BigDecimal` for the monetary comparison rather than an imprecise `Double`.
     */
    val amountTolerancePercent: String = "0",
)
