package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.repository.ReportedBalanceReadRepository
import kotlin.time.Instant

/** What a record's statement says its account held once the record had happened. */
data class ReportedBalanceWrite(
    val recordId: ImportRecordId,
    val accountId: AccountId,
    val timestamp: Instant,
    val amount: Money,
    val source: Source,
)

interface ReportedBalanceWriteRepository : ReportedBalanceReadRepository {
    /**
     * Writes each record's balance: a new one at revision 1, a changed one as its next revision (its provenance recorded
     * from its [ReportedBalanceWrite.source] either way), an unchanged one not at all. Each is unreserved.
     */
    suspend fun writeBalances(balances: List<ReportedBalanceWrite>)

    /**
     * Deletes the balances a settled re-run didn't report again. Reserving a staged file or API session for its re-run
     * reserves its records' balances along with their observations.
     */
    suspend fun deleteReserved()
}
