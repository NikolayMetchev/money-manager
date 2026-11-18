package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.write.AccountWriteRepository
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.importengineapi.MaterializationReport

/** Applies the observation and effective row changes computed by [ObservationMaterializationPlanner]. */
internal class ObservationMaterialization(
    private val observations: ObservationWriteRepository,
    private val accounts: AccountWriteRepository,
    private val trades: TradeReadRepository,
) {
    suspend fun apply(plan: ObservationMaterializationPlan): MaterializationReport {
        val ids = observations.applyObservationChanges(plan.newTransactions.values, plan.observations, plan.rows)

        fun resolved(id: TransactionId): TransactionId = ids[id] ?: id
        return plan.report.copy(
            newTransactionIds = plan.newTransactions.mapValues { (_, id) -> resolved(id) },
            inserted = plan.report.inserted.mapTo(mutableSetOf(), ::resolved),
            deletedAccountIds = collectAccounts(plan.candidateAccountIds),
        )
    }

    /**
     * Deletes the [candidates] nothing references (no transfer, trade or observation), then the fee accounts of those
     * that nothing references either: a fee account belongs to its account and goes with it.
     */
    private suspend fun collectAccounts(candidates: Set<AccountId>): Set<AccountId> {
        if (candidates.isEmpty()) return emptySet()
        val unreferenced = unreferenced(candidates)
        if (unreferenced.isEmpty()) return emptySet()
        val feeAccounts =
            unreferenced
                .flatMap { accounts.getAccountIdsByAttribute(WellKnownIds.ACCOUNT_FEE_ACCOUNT_FOR_ATTR_TYPE_ID, it.id.toString()) }
                .toSet() - unreferenced
        val garbage = unreferenced + unreferenced(feeAccounts)
        accounts.deleteAccounts(garbage)
        return garbage
    }

    private suspend fun unreferenced(accountIds: Set<AccountId>): Set<AccountId> {
        if (accountIds.isEmpty()) return emptySet()
        return accountIds -
            accounts.accountsWithTransfers(accountIds) -
            trades.accountsWithTrades(accountIds) -
            observations.getAccountsWithObservations(accountIds)
    }
}
