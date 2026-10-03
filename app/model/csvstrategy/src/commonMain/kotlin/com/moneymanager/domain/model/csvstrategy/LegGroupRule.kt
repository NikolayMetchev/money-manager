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
 * vocabulary behind every multi-row CSV movement; a strategy lists as many as its source needs, and a
 * row is a leg of the first rule that claims it.
 *
 * A row is a leg when all of [legWhen] hold. Its [side] says whether it is a debit (an asset leaving the
 * owner account) or a credit (an asset arriving). Legs whose [key] parts agree and that lie within
 * [windowSeconds] of each other describe one event; [assembly] says what that event becomes.
 *
 * Leg conditions see each cell trimmed, as the source's own whitespace says nothing about which leg a
 * row is. Patterns are matched case-insensitively and anywhere ([Regex.containsMatchIn]), so anchor
 * them (`^…$`) unless a prefix match is genuinely wanted — an unanchored `Buy` would also claim
 * `Transaction Buy`. A blank cell is a leg only when a condition explicitly admits it.
 *
 * @property reconcileWindowSeconds When set, an event another source already recorded — as trades, or as
 *   the individual fills a trade group aggregates — is not booked again. Deliberately **not** the
 *   strategy's `crossSourceReconcileWindowSeconds`: that window has to be wide enough for a bank's
 *   settlement lag, and a wide window here would pull a later event's legs into the candidate set and
 *   stop the sums matching at all. Two sources disagree about an event's instant only by sub-second
 *   rounding, so keep this to a few seconds. Null disables it.
 */
@Serializable
data class LegGroupRule(
    @Serializable(with = SortedConditionListSerializer::class)
    val legWhen: List<Condition>,
    val side: LegSide,
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

/** Which side of its event a leg is on. */
@Serializable
sealed interface LegSide {
    /**
     * The sign of the value at [path]: negative is a debit, positive a credit, and a zero or unparseable
     * value is no leg at all — for a source that names both legs identically (Binance's `Transaction
     * Related`, Bybit's blank Convert type).
     */
    @Serializable
    @SerialName("sign")
    data class Sign(
        val path: String,
    ) : LegSide

    /** A debit when all of [conditions] hold, otherwise a credit. */
    @Serializable
    @SerialName("debitWhen")
    data class DebitWhen(
        @Serializable(with = SortedConditionListSerializer::class)
        val conditions: List<Condition>,
    ) : LegSide
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
     * @property accounts Picks the intermediate account: the first applicable rule's name. A leg no rule
     *   names is not a leg.
     * @property relationshipTypeName Links each debit to its credit (resolved get-or-create).
     */
    @Serializable
    @SerialName("throughAccount")
    data class ThroughAccount(
        // First applicable rule wins - order is semantic, keeps insertion-order serialization.
        val accounts: List<AccountRule>,
        val relationshipTypeName: String,
    ) : LegAssembly
}
