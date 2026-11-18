package com.moneymanager.apiimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.deleteEmptyImportCreatedAccounts

/** What unimporting an API session will do, executed verbatim by [executeApiUnimport]. */
data class ApiUnimportPlan(
    val sessionId: ApiSessionId,
    /** The transfers the unimport deletes: those nothing outside the session still observes. */
    val transferIds: Set<TransferId>,
    val tradeIds: Set<TradeId>,
    /** Accounts the session created; those left with no activity are deleted afterwards. */
    val createdAccountIds: Set<AccountId>,
)

/** Outcome of [executeApiUnimport]. */
data class ApiUnimportResult(
    val deletedTransfers: Int,
    val deletedTrades: Int,
    val deletedEmptyAccounts: List<String>,
)

/**
 * Works out what unimporting [sessionId] removes, as a staged file's unimport does: the session's records are withdrawn,
 * and a transaction goes only once nothing else observes it. So it deletes the transactions the session created, or
 * observes and an import or the match created, that no staged file's or other session's record, no person and no leg
 * group outside the session still observes. One still observed stays, rebuilt from what the others say.
 */
suspend fun planApiUnimport(
    sessionId: ApiSessionId,
    apiSessionRepository: ApiSessionReadRepository,
): ApiUnimportPlan {
    val candidates =
        apiSessionRepository.getTransferIdsCreatedBySession(sessionId) +
            apiSessionRepository.getTradeIdsCreatedBySession(sessionId) +
            apiSessionRepository.getTransactionsObservedBySession(sessionId)
    val deleted = candidates - apiSessionRepository.getTransactionsObservedOutsideSessions(setOf(sessionId), candidates)
    return ApiUnimportPlan(
        sessionId = sessionId,
        transferIds = deleted.filterIsInstanceTo(mutableSetOf()),
        tradeIds = deleted.filterIsInstanceTo(mutableSetOf()),
        createdAccountIds = apiSessionRepository.getAccountIdsCreatedBySession(sessionId),
    )
}

/**
 * Unimports a session per [plan]: deletes the transactions only it observed, un-hides transfers from other sources its
 * transfers had reconciled away, returns the session to downloaded but never imported (its responses are kept, so it can
 * be imported again), and deletes the accounts it created that are left empty.
 */
@Suppress("LongParameterList")
suspend fun executeApiUnimport(
    plan: ApiUnimportPlan,
    accountRepository: AccountReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    refreshViews: Boolean = true,
): ApiUnimportResult {
    val source = Source.Api(plan.sessionId)
    // One batch, so the session never reads as unimported while its transactions remain, nor the reverse.
    importEngine.import(
        ImportBatch(
            transfers =
                plan.transferIds.map { id -> ImportTransfer(source = source, operation = ImportOperation.DELETE, existingId = id) },
            trades =
                plan.tradeIds.map { id ->
                    ImportTradeIntent(
                        key = LocalTradeKey("api-unimport-delete-${id.id}"),
                        source = source,
                        operation = ImportOperation.DELETE,
                        existingId = id,
                    )
                },
            dedupePolicy = DedupePolicy.None,
            apiSessionMutations = listOf(ApiSessionMutation.ResetToUnimported(plan.sessionId)),
            // What the session's records were matched or linked with is matched again without them, as a fresh import of
            // the others would.
            syncMatches = true,
        ),
    )
    val deletedEmptyAccounts =
        importEngine.deleteEmptyImportCreatedAccounts(
            createdAccountIds = plan.createdAccountIds,
            source = source,
            accountRepository = accountRepository,
            tradeRepository = tradeRepository,
            keyPrefix = "api-unimport-delete",
        )
    if (refreshViews) maintenance.refreshMaterializedViews()
    return ApiUnimportResult(
        deletedTransfers = plan.transferIds.size,
        deletedTrades = plan.tradeIds.size,
        deletedEmptyAccounts = deletedEmptyAccounts,
    )
}
