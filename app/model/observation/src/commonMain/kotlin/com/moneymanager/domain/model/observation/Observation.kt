package com.moneymanager.domain.model.observation

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@JvmInline
value class ObservationId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

/** An asset an observation names, by id alone: observations hold references, not loaded assets. */
@JvmInline
value class ObservedAssetId(
    override val id: Long,
) : AssetId {
    override fun toString() = id.toString()
}

/** Who says what an observation says. Each owner says one thing per slot. */
sealed interface ObservationOwner {
    /** One import record. */
    data class Record(
        val recordId: ImportRecordId,
    ) : ObservationOwner

    /** The records of an import group together. */
    data class Group(
        val groupId: ImportGroupId,
    ) : ObservationOwner

    /** A person, who created a transaction by hand; [entitySourceId] is that creation's provenance row. */
    data class Manual(
        val entitySourceId: Long,
    ) : ObservationOwner
}

/** The values an observation reports, shaped like the transfer or trade row they merge into. */
sealed interface ObservedValues {
    val kind: TransactionKind

    data class Transfer(
        val timestamp: Instant,
        val description: String,
        val sourceAccountId: AccountId,
        val targetAccountId: AccountId,
        val assetId: ObservedAssetId,
        val amount: BigInteger,
    ) : ObservedValues {
        override val kind get() = TransactionKind.TRANSFER
    }

    data class Trade(
        val timestamp: Instant,
        val description: String,
        val fromAccountId: AccountId,
        val fromAssetId: ObservedAssetId,
        val fromAmount: BigInteger,
        val toAccountId: AccountId,
        val toAssetId: ObservedAssetId,
        val toAmount: BigInteger,
    ) : ObservedValues {
        override val kind get() = TransactionKind.TRADE
    }
}

/** An attribute an observation reports; [groupKey] is `""` when ungrouped, as on `transfer_attribute`. */
data class ObservationAttribute(
    val typeId: AttributeTypeId,
    val groupKey: String,
    val value: String,
)

/** An end of a transfer (a trade's from and to). */
enum class ObservationEnd(
    val dbName: String,
) {
    SOURCE("source"),
    TARGET("target"),
    ;

    companion object {
        fun fromDbName(name: String): ObservationEnd = entries.first { it.dbName == name }
    }
}

/**
 * The facts the rank-merge reads: precise beats [approximateTime], named beats a [placeholderEnds] end, itemised
 * beats [grossAmount], and a source's own account beats what it says about the other end ([ownEnd]).
 *
 * @property ownEnd the end that is the record's own statement account (the account whose statement or API session
 *   the record comes from), when it is one of the two: a bank says for certain which of its own accounts moved, and
 *   only guesses the counterparty's.
 */
data class ObservationFacts(
    val approximateTime: Boolean = false,
    val placeholderEnds: Set<ObservationEnd> = emptySet(),
    val grossAmount: Boolean = false,
    val ownEnd: ObservationEnd? = null,
)

/** What an owner says about one slot. */
data class ObservationContent(
    val values: ObservedValues,
    val attributes: Set<ObservationAttribute> = emptySet(),
    val facts: ObservationFacts = ObservationFacts(),
)

/**
 * What [owner] says about its output [slot] (`main`, `fee`, `conduit/<n>`, `reversal`, `trade`, `manual-entry`),
 * assigned to [transactionId]: a `TransferId` or a `TradeId`, by the values' kind.
 */
data class Observation(
    val id: ObservationId,
    val owner: ObservationOwner,
    val slot: String,
    val transactionId: TransactionId,
    val content: ObservationContent,
)

/** A reserved observation whose transaction row has been vacated for a re-import. */
data class VacantObservation(
    val id: ObservationId,
    val owner: ObservationOwner,
    val slot: String,
    val transactionId: TransactionId,
)

/** What every slot of a reconciliation copy (#1475) starts with: `copy/<slot>` is the copy of the owner's `<slot>`. */
const val COPY_SLOT_PREFIX = "copy/"
