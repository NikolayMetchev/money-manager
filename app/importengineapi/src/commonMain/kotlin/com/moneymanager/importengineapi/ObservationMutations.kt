package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservationPin
import kotlin.jvm.JvmInline

/** Builder-chosen placeholder identity for a transaction this batch's observations start, before its id exists. */
@JvmInline
value class LocalTransactionKey(
    val value: String,
)

/** The transaction an observation is on: one that exists, or a new one this batch allocates. */
sealed interface ObservationTransaction {
    data class Existing(
        val id: TransactionId,
    ) : ObservationTransaction

    /** Every observation naming one [key] lands on the same new transaction, of their kind. */
    data class New(
        val key: LocalTransactionKey,
    ) : ObservationTransaction
}

/** One change to what an owner says about one of its slots. */
sealed interface ObservationMutation {
    val owner: ObservationOwner
    val slot: String

    /** [owner] says [content] about [slot], on [transaction]: recorded, or replacing what it said before. */
    data class Upsert(
        override val owner: ObservationOwner,
        override val slot: String,
        val transaction: ObservationTransaction,
        val content: ObservationContent,
    ) : ObservationMutation

    /** [owner] no longer says anything about [slot]. */
    data class Delete(
        override val owner: ObservationOwner,
        override val slot: String,
    ) : ObservationMutation
}

/**
 * Brings the materialized rows up to date after the batch's [ImportBatch.observationMutations]: every transaction
 * a mutation touched, plus [transactionIds] (e.g. whose overrides changed), is rank-merged with its overrides on
 * top and written as a diff, recorded as written by [source]. Then [candidateAccountIds] that nothing references
 * any more are deleted, each with its fee account.
 */
data class MaterializationRequest(
    val source: Source,
    val transactionIds: Set<TransactionId> = emptySet(),
    val candidateAccountIds: Set<AccountId> = emptySet(),
)

/** What a materialization wrote, and what it left for a person. */
data class MaterializationReport(
    val newTransactionIds: Map<LocalTransactionKey, TransactionId> = emptyMap(),
    val inserted: Set<TransactionId> = emptySet(),
    val updated: Set<TransactionId> = emptySet(),
    val deleted: Set<TransactionId> = emptySet(),
    /** Overridden values whose import value changed underneath: the overrides still won. */
    val conflicts: List<MaterializationConflict> = emptyList(),
    /** Pins onto a deleted transaction; the observation is no longer there to hold. */
    val orphanedPins: List<ObservationPin> = emptyList(),
    /** Reconciliation matches that lost a member, left as they are for a person to resolve. */
    val orphanedMatches: List<Relationship> = emptyList(),
    /** Other relationships that lost a member, likewise left as they are. */
    val orphanedRelationships: List<Relationship> = emptyList(),
    /** Import-created accounts nothing references any more, deleted with their fee accounts. */
    val deletedAccountIds: Set<AccountId> = emptySet(),
) {
    /** Whether the materialization wrote any transaction row. */
    val wroteRows: Boolean get() = inserted.isNotEmpty() || updated.isNotEmpty() || deleted.isNotEmpty()
}
