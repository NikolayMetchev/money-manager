package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.repository.TradeReadRepository
import kotlin.time.Instant

/**
 * Outcome of creating one [NewTrade]: the trade's id plus whether a new row was inserted.
 * [created] is false when an identical trade already existed (the idempotent re-import/cross-source
 * path), letting callers surface the row as a duplicate instead of a fresh import.
 */
data class TradeCreateResult(
    val id: TradeId,
    val created: Boolean,
)

/**
 * A cross-asset trade to create: [fromAmount] leaves [fromAccountId], [toAmount] enters [toAccountId] (the
 * two [Money] legs may be denominated in different assets). [attributes] are set on it, replacing an
 * existing value of the same type (on a reused trade too).
 *
 * Idempotent as a **multiset**, not a set: [occurrence] (0-based) is the caller's count of how many earlier
 * trades in this same create pass already matched this exact field tuple (e.g. an exchange order split into
 * several byte-identical fills). The occurrence-th existing identical trade is reused
 * ([TradeCreateResult.created] = false); once existing matches are exhausted, a new row is inserted. This
 * lets N genuinely-repeated identical fills book as N distinct trades while re-importing the same N fills
 * still dedupes to the same N rows instead of only one.
 */
data class NewTrade(
    val timestamp: Instant,
    val description: String,
    val fromAccountId: AccountId,
    val fromAmount: Money,
    val toAccountId: AccountId,
    val toAmount: Money,
    val source: Source,
    val occurrence: Int = 0,
    val attributes: List<NewAttribute> = emptyList(),
)

interface TradeWriteRepository : TradeReadRepository {
    /**
     * Creates [trades] in one database transaction, in order: allocates each a `transaction_id`, inserts it
     * (or reuses an identical one, see [NewTrade.occurrence]), sets its attributes and records provenance.
     * Returns one result per trade, in the same order.
     */
    suspend fun createTrades(trades: List<NewTrade>): List<TradeCreateResult>

    /** Deletes [ids] in one database transaction: one commit for the lot rather than one per trade. */
    suspend fun deleteTrades(ids: Collection<TradeId>)

    /**
     * A person's edit of trade [id]'s attribute of type [typeId]: sets it to [value], or removes it when
     * [value] is null. A change gives the trade a new revision recorded with [source] and a
     * `trade_attribute_audit` row, so a re-import knows to put it back (see [getHandSetAttributes]); setting
     * the value it already has writes nothing.
     */
    suspend fun setAttributeByHand(
        id: TradeId,
        typeId: AttributeTypeId,
        value: String?,
        source: Source,
    )

    /** Removes trade [id]'s attribute of type [typeId], if any, as an import's own change (no revision). */
    suspend fun removeAttribute(
        id: TradeId,
        typeId: AttributeTypeId,
    )
}
