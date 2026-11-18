package com.moneymanager.domain.model.observation

import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind

/**
 * A transaction's materialized row: the `transfer` or `trade` values and its attributes, the shape the materializer
 * computes from the transaction's observations and writes. A trade's attributes have the group key `""`.
 */
data class MaterializedTransaction(
    val values: ObservedValues,
    val attributes: Set<ObservationAttribute> = emptySet(),
)

/** One write the materializer makes to a transaction's row, keyed by its `transaction_id`. */
sealed interface MaterializedWrite {
    val transactionId: Long

    /** The transaction has observations but no row yet: insert [row] with this id, recorded as written by [source]. */
    data class Insert(
        override val transactionId: Long,
        val row: MaterializedTransaction,
        val source: Source,
    ) : MaterializedWrite

    /** The row differs from [after]: a new revision, the same id, recorded as written by [source]. */
    data class Update(
        override val transactionId: Long,
        val before: MaterializedTransaction,
        val after: MaterializedTransaction,
        val source: Source,
    ) : MaterializedWrite

    /** No observation is left on the transaction: its row goes; the `transaction_id` stays for its overrides. */
    data class Delete(
        override val transactionId: Long,
        val kind: TransactionKind,
    ) : MaterializedWrite
}

/** A slot's stored observation and its planned replacement. */
data class ObservationChange(
    val owner: ObservationOwner,
    val slot: String,
    val before: Observation?,
    val after: ObservationBooking?,
)

data class ObservationBooking(
    val storedId: ObservationId?,
    val transactionId: TransactionId,
    val content: ObservationContent,
)
