package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.withStatementAccount
import com.moneymanager.domain.repository.write.ReportedBalanceWrite
import com.moneymanager.domain.repository.write.ReportedBalanceWriteRepository
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.LocalAccountKey

/**
 * Stores the balances a batch's records report (the `balance` rule action), each keyed by its record, so it lives and
 * goes with the record whatever the record books. Its provenance is the record's own row or API item.
 */
internal class ReportedBalanceWriter(
    private val balances: ReportedBalanceWriteRepository,
) {
    suspend fun write(
        batch: ImportBatch,
        observer: SourceObservations,
        keyToId: Map<LocalAccountKey, AccountId>,
    ) {
        if (batch.reportedBalances.isEmpty()) return
        val writes =
            batch.reportedBalances.map { balance ->
                val account = resolveRef(balance.account, keyToId).requireExistingId()
                ReportedBalanceWrite(
                    recordId = observer.recordId(balance.record),
                    accountId = account,
                    timestamp = balance.timestamp,
                    amount = balance.amount,
                    source = balance.record.source().withStatementAccount(account),
                )
            }
        balances.writeBalances(writes)
    }

    /** Deletes what a settled re-run's records didn't report again. */
    suspend fun settle() = balances.deleteReserved()
}

private fun ImportRecordKey.source(): Source =
    when (this) {
        is ImportRecordKey.CsvRow -> Source.Csv(csvImportId, rowIndex)
        is ImportRecordKey.ApiItem -> Source.Api(sessionId, requestId, JsonPath(jsonPath))
    }
