package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.MaterializedTransaction
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.VacantObservation

/** Read side of observations: what each owner says about each of its slots, and which transaction it is on. */
interface ObservationReadRepository {
    suspend fun getObservation(id: ObservationId): Observation?

    /** Every observation [owner] owns, by slot. */
    suspend fun getObservationsOf(owner: ObservationOwner): List<Observation>

    suspend fun getObservationsOfRecords(recordIds: Collection<ImportRecordId>): List<Observation>

    suspend fun getReservedIds(ids: Collection<ObservationId>): Set<ObservationId>

    /**
     * The provenance row that created [transactionId] (its first revision's writer): a hand-made transaction's
     * observations are owned by it (`ObservationOwner.Manual`).
     */
    suspend fun getCreationSourceId(transactionId: TransactionId): Long?

    /** Every observation assigned to [transactionId], by id. */
    suspend fun getObservationsOfTransaction(transactionId: TransactionId): List<Observation>

    suspend fun getObservationsOfTransactions(transactionIds: Collection<TransactionId>): List<Observation>

    /** Transactions observed by records of the given files or sessions, including reserved observations. */
    suspend fun getRecordTransactions(
        csvImports: Set<CsvImportId>,
        apiSessions: Set<ApiSessionId>,
    ): Set<TransactionId>

    /** Transactions with an active record observation belonging to a source outside the given files and sessions. */
    suspend fun getTransactionsObservedByOtherRecords(
        transactions: Collection<TransactionId>,
        csvImports: Set<CsvImportId>,
        apiSessions: Set<ApiSessionId>,
    ): Set<TransactionId>

    /** Every observation naming one of [accountIds] (either end of a transfer, either side of a trade), by id. */
    suspend fun getObservationsOnAccounts(accountIds: Collection<AccountId>): List<Observation>

    /** The files and API sessions whose records have an observation naming [accountId]. */
    suspend fun getRecordSourcesOnAccount(accountId: AccountId): ObservedRecordSources

    /** The `transfer` or `trade` row [transactionId] has, with its attributes, or null when it has none. */
    suspend fun getMaterialized(transactionId: TransactionId): MaterializedTransaction?

    /** The IDs among [transactionIds] that currently have a transfer or trade row. */
    suspend fun getMaterializedIds(transactionIds: Collection<TransactionId>): Set<TransactionId>

    /** The accounts among [accountIds] that some observation names. */
    suspend fun getAccountsWithObservations(accountIds: Collection<AccountId>): Set<AccountId>

    /**
     * The observations a re-run reserved (#1430) on a transaction nothing else says: the ids its records may take back. The
     * transaction's row stays as it was until the re-run settles (#1510). Reservations only exist while a re-run is under way.
     */
    suspend fun getVacantReserved(): List<VacantObservation>

    suspend fun getVacantReservedRecords(recordIds: Collection<ImportRecordId>): List<VacantObservation>

    suspend fun getVacantReservedGroups(): List<VacantObservation>

    /**
     * Those of [among] every observation of which a re-run reserved (#1510): what its own dedupe and matching don't see.
     * Scoped to the caller's ids, as a re-run can leave tens of thousands vacant and every batch asks.
     */
    suspend fun getVacantTransactionIds(among: Collection<TransactionId>): Set<TransactionId>

    /** Every observation still reserved, vacant or not: what settling a re-run resolves. */
    suspend fun getReserved(): List<Observation>

    /** Whether a re-run reserved observation [id] (#1430) and its record hasn't said it again yet. */
    suspend fun isReserved(id: ObservationId): Boolean
}

/** The files and API sessions that say something about an account. */
data class ObservedRecordSources(
    val csvImports: Set<CsvImportId>,
    val apiSessions: Set<ApiSessionId>,
)
