package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.SortedConditionListSerializer
import com.moneymanager.domain.model.rules.ValueExpr
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One way a source splits a single movement across several rows — the legs of a trade, or the
 * debited/credited rows of a conversion — and how those rows are put back together. The one grouping
 * vocabulary behind every multi-row CSV movement; a strategy lists as many as its source needs.
 *
 * The strategy's rules decide which rows are legs: a `RuleAction.Leg` naming this group's [name] makes
 * its row a debit (an asset leaving the owner account) or a credit (an asset arriving), and a row is a
 * leg of the first group it claims whose role resolves. Legs whose [key] parts agree and that lie within
 * [windowSeconds] of each other describe one event; [assembly] says what that event becomes.
 *
 * @property name The group's name, which the rules' leg actions refer to.
 * @property reconcileWindowSeconds When set, an event another source already recorded — as trades, or as
 *   the individual fills a trade group aggregates — is not booked again. Deliberately **not** the
 *   strategy's `crossSourceReconcileWindow`: that window has to be wide enough for a bank's
 *   settlement lag, and a wide window here would pull a later event's legs into the candidate set and
 *   stop the sums matching at all. Two sources disagree about an event's instant only by sub-second
 *   rounding, so keep this to a few seconds. Null disables it.
 */
@Serializable
data class LegGroupRule(
    val name: String,
    // Parts are joined in order to form the key - order is semantic, keeps insertion-order serialization.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val key: List<ValueExpr> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val windowSeconds: Long = 0,
    val assembly: LegAssembly,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reconcileWindowSeconds: Long? = null,
) {
    init {
        require(windowSeconds >= 0) { "LegGroupRule.windowSeconds must not be negative" }
        // A negative window would not disable reconciliation, it would silently defeat it: every check
        // compares a non-negative absolute time difference against it, so nothing could ever match and
        // events another source already recorded would be booked again. Null is how you turn it off.
        require(reconcileWindowSeconds == null || reconcileWindowSeconds >= 0) {
            "LegGroupRule.reconcileWindowSeconds must not be negative"
        }
    }
}

/** What one event's legs become. */
@Serializable
sealed interface LegAssembly {
    /**
     * One `trade` on the owner account, when the event's debits all name one asset and its credits all
     * name one other asset: debit sum out, credit sum in. Legs chain into one event while each lies within
     * the window of the previous one. An event that does not resolve — an empty side, more than one asset
     * on a side, a zero total — is left alone and its rows import as ordinary transfers, so no row is ever
     * dropped and the residue stays visible.
     *
     * A `trade` carries no fee, so fee rows belong in no leg rule: the strategy's ordinary account routing
     * books them as their own transfer to a fee account.
     *
     * @property description The trade's description: `{from}` and `{to}` become the debited and credited
     *   asset codes. Cosmetic only — a trade's identity never includes its description.
     */
    @Serializable
    @SerialName("trade")
    data class Trade(
        val description: String = "Buy {to}/{from}",
    ) : LegAssembly

    /**
     * Each leg stays a single-asset transfer between the owner account and an intermediate account, and
     * each debit is linked to its nearest credit within the window by a [relationshipTypeName]
     * relationship — for conversions whose legs cannot be attributed to each other (a many-assets-in /
     * one-asset-out dust sweep, where no column says which credit came from which debit), so balances stay
     * exact without inventing a pairing.
     *
     * The leg's rule names the intermediate account with a `RuleAction.Account` on the intermediate side;
     * a leg whose candidates all fail to name one is not a leg. With no such candidate, each leg keeps the
     * counterparty its own row names and the assembly only links the legs — for a source that records one
     * movement as two rows through the owner account, such as PayPal's card deposit and the payment it
     * funded (a `pass-through` pair).
     *
     * @property relationshipTypeName Links each debit to its credit (resolved get-or-create).
     * @property fundingWhen When set, an event is one leg matching these conditions (the funding: the
     *   relationship's first transfer) and one leg that doesn't, paired one to one — the funding leg on
     *   either side, so a refund's withdrawal links to its refund the same way a deposit links to its payment.
     *   Empty: each debit links to its nearest credit.
     */
    @Serializable
    @SerialName("throughAccount")
    data class ThroughAccount(
        val relationshipTypeName: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @Serializable(with = SortedConditionListSerializer::class)
        val fundingWhen: List<Condition> = emptyList(),
    ) : LegAssembly
}
