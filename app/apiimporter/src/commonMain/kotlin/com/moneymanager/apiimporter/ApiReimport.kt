package com.moneymanager.apiimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.markApiSessionImported
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/** Value an internal-transfer reconcile's exclusion attribute always carries (see `ImportDeduper`). */
private const val EXCLUDED_ATTR_VALUE = "reconciled"

/**
 * What re-importing an already-imported API session will delete before re-running it: the
 * transfers/trades/accounts THIS session (and only this session) created, per `entity_source`.
 * Purely informational — a UI preview. [executeApiReimport] recomputes nothing beyond these ids.
 */
data class ApiReimportPlan(
    val sessionId: ApiSessionId,
    val transferIds: Set<TransferId>,
    val tradeIds: Set<TradeId>,
    val accountIds: Set<AccountId>,
) {
    val isEmpty: Boolean get() = transferIds.isEmpty() && tradeIds.isEmpty() && accountIds.isEmpty()
}

suspend fun planApiReimport(
    sessionId: ApiSessionId,
    apiSessionRepository: ApiSessionReadRepository,
): ApiReimportPlan =
    ApiReimportPlan(
        sessionId = sessionId,
        transferIds = apiSessionRepository.getTransferIdsCreatedBySession(sessionId),
        tradeIds = apiSessionRepository.getTradeIdsCreatedBySession(sessionId),
        accountIds = apiSessionRepository.getAccountIdsCreatedBySession(sessionId),
    )

/** Outcome of [executeApiReimport]. */
data class ApiReimportResult(
    val transfersDeleted: Int,
    val tradesDeleted: Int,
    val unexcludedPartners: Int,
    val deletedEmptyAccounts: List<String>,
    val transactionsImported: Int,
    val tradesImported: Int,
    val peopleImported: Int,
)

/**
 * Re-imports [session] under its current [strategy]: deletes every transfer/trade the session itself
 * created (per [plan]), un-excludes any bank-side partner leg an internal-transfer reconcile had
 * hidden whose only remaining reconcile link was to one of the deleted transfers, then re-runs the
 * normal import — which re-parses the session's stored responses under the current strategy config
 * (bridges, aliases, mappings) from scratch.
 *
 * Deletion-then-rerun mirrors why CSV re-import deletes before re-running
 * (`CsvReimport.executeCsvReimport`): with the session's own transfers still present, the deduper
 * would classify the freshly re-parsed rows DUPLICATE against themselves (by `apiId`) before any
 * reconcile logic gets a chance to run — see `ImportDeduper.classifyByApiMultiKey`.
 */
@Suppress("LongParameterList")
suspend fun executeApiReimport(
    plan: ApiReimportPlan,
    session: ApiSession,
    strategy: ApiImportStrategy,
    apiSessionRepository: ApiSessionReadRepository,
    accountRepository: AccountReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    counterpartyAccountNames: Map<String, String> = emptyMap(),
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    refreshViews: Boolean = true,
): ApiReimportResult {
    require(plan.sessionId == session.id) {
        "plan is for session ${plan.sessionId} but executeApiReimport was called with session ${session.id}"
    }
    val importStartedAt = Clock.System.now()

    // Snapshot RECONCILED-relationship partners of every to-be-deleted transfer BEFORE deleting: the
    // relationship row cascades away with the transfer, but a partner's EXCLUDED attribute does not —
    // left alone, that leg would stay hidden with no partner to explain it.
    val existingRelationships =
        if (plan.transferIds.isEmpty()) emptyList() else transferRelationshipRepository.getByTransfers(plan.transferIds)
    val reconciledPartners =
        existingRelationships
            .filter { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
            .flatMap { listOf(it.id1, it.id2) }
            .filterNot { it in plan.transferIds }
            .toSet()

    onProgress?.invoke(ImportProgress("Removing session's transactions"))
    importEngine.import(
        ImportBatch(
            transfers =
                plan.transferIds.map { id ->
                    ImportTransfer(source = Source.Api(session.id), operation = ImportOperation.DELETE, existingId = id)
                },
            trades =
                plan.tradeIds.map { id ->
                    ImportTradeIntent(
                        key = LocalTradeKey("reimport-delete-${id.id}"),
                        source = Source.Api(session.id),
                        operation = ImportOperation.DELETE,
                        existingId = id,
                    )
                },
            dedupePolicy = DedupePolicy.None,
            apiSessionMutations = listOf(ApiSessionMutation.DeleteResponseTransactionsBySession(session.id)),
        ),
    )

    // A surviving partner un-excludes only once it has no RECONCILED relationship left at all — one
    // reconciled against more than one deleted session leg (unusual, but possible) must stay hidden
    // until every one of them is gone.
    val unexcluded =
        if (reconciledPartners.isEmpty()) {
            emptySet()
        } else {
            val remainingRelationships = transferRelationshipRepository.getByTransfers(reconciledPartners)
            val stillReconciled =
                remainingRelationships
                    .filter { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
                    .flatMap { listOf(it.id1, it.id2) }
                    .toSet()
            val toUnexclude = reconciledPartners - stillReconciled
            if (toUnexclude.isEmpty()) {
                emptySet()
            } else {
                val partnerTransfers = transactionRepository.getTransactionsByIds(toUnexclude)
                val updates =
                    toUnexclude.mapNotNull { id ->
                        val attr =
                            partnerTransfers[id]?.attributes?.firstOrNull {
                                it.attributeType.id.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID && it.value == EXCLUDED_ATTR_VALUE
                            } ?: return@mapNotNull null
                        ImportTransfer(
                            source = Source.System,
                            operation = ImportOperation.UPDATE,
                            existingId = id,
                            deletedAttributeIds = setOf(attr.id),
                        )
                    }
                if (updates.isNotEmpty()) {
                    onProgress?.invoke(ImportProgress("Un-hiding reconciled transactions"))
                    importEngine.import(ImportBatch(transfers = updates, dedupePolicy = DedupePolicy.None))
                }
                // Only the transfers actually represented by an update (i.e. that still carried the
                // exclusion attribute) were un-excluded — a candidate missing it contributes nothing.
                updates.mapNotNull { it.existingId }.toSet()
            }
        }

    onProgress?.invoke(ImportProgress("Re-importing"))
    val rerun =
        if (strategy.syntheticAccount != null) {
            val exchangeResult =
                importApiSessionExchange(
                    apiSessionRepository = apiSessionRepository,
                    accountRepository = accountRepository,
                    currencyRepository = currencyRepository,
                    cryptoRepository = cryptoRepository,
                    sessionId = session.id,
                    strategy = strategy,
                    importEngine = importEngine,
                )
            RerunOutcome(transactions = exchangeResult.transfersImported, trades = exchangeResult.tradesImported, people = 0)
        } else {
            val transactionsResult =
                importApiSessionTransactions(
                    apiSessionRepository = apiSessionRepository,
                    currencyRepository = currencyRepository,
                    sessionId = session.id,
                    strategy = strategy,
                    importEngine = importEngine,
                    counterpartyAccountNames = counterpartyAccountNames,
                    passThroughAccounts = passThroughAccounts,
                )
            val peopleResult =
                if (strategy.peopleDownload != null) {
                    importApiSessionPeople(
                        apiSessionRepository = apiSessionRepository,
                        accountRepository = accountRepository,
                        accountAttributeRepository = accountAttributeRepository,
                        importEngine = importEngine,
                        sessionId = session.id,
                        strategy = strategy,
                        accountsSessionId = session.id,
                    )
                } else {
                    null
                }
            RerunOutcome(
                transactions = transactionsResult.transactionCount,
                trades = 0,
                people = peopleResult?.personCount ?: 0,
            )
        }

    importEngine.markApiSessionImported(
        id = session.id,
        revisionId = strategy.revisionId,
        importedAt = Clock.System.now(),
        importDurationMillis = (Clock.System.now() - importStartedAt).inWholeMilliseconds,
    )

    onProgress?.invoke(ImportProgress("Cleaning up empty accounts"))
    val deletedEmptyAccounts =
        deleteEmptyAccountsCreatedBySession(session.id, plan.accountIds, accountRepository, tradeRepository, importEngine)

    if (refreshViews) {
        onProgress?.invoke(ImportProgress("Refreshing views"))
        maintenance.refreshMaterializedViews()
    }

    return ApiReimportResult(
        transfersDeleted = plan.transferIds.size,
        tradesDeleted = plan.tradeIds.size,
        unexcludedPartners = unexcluded.size,
        deletedEmptyAccounts = deletedEmptyAccounts,
        transactionsImported = rerun.transactions,
        tradesImported = rerun.trades,
        peopleImported = rerun.people,
    )
}

private data class RerunOutcome(
    val transactions: Int,
    val trades: Int,
    val people: Int,
)

/**
 * Deletes accounts this session created (per [accountIds]) that hold no transactions. Trades count
 * as activity: deleting an account cascades to its trades (`trade.from_account_id`/`to_account_id`
 * are `ON DELETE CASCADE`), so an account whose only movements are trades must survive.
 */
private suspend fun deleteEmptyAccountsCreatedBySession(
    sessionId: ApiSessionId,
    accountIds: Set<AccountId>,
    accountRepository: AccountReadRepository,
    tradeRepository: TradeReadRepository,
    importEngine: ImportEngine,
): List<String> {
    if (accountIds.isEmpty()) return emptyList()
    val remainingById = accountRepository.getAllAccounts().first().associateBy { it.id }
    val candidates = accountIds.mapNotNull { remainingById[it] }
    if (candidates.isEmpty()) return emptyList()
    val withTransfers = accountRepository.accountsWithTransfers(candidates.map { it.id })
    val withTrades = tradeRepository.accountsWithTrades(candidates.map { it.id })
    val emptyAccounts = candidates.filter { it.id !in withTransfers && it.id !in withTrades }
    if (emptyAccounts.isEmpty()) return emptyList()

    importEngine.import(
        ImportBatch.manualEdits(
            accounts =
                emptyAccounts.map { account ->
                    ImportAccountIntent(
                        key = LocalAccountKey("reimport-delete-${account.id.id}"),
                        source = Source.Api(sessionId),
                        operation = ImportOperation.DELETE,
                        existingId = account.id,
                    )
                },
        ),
    )
    return emptyAccounts.map { it.name }
}

/** Aggregated outcome of [bulkReimportApiSessions]. */
data class ApiBulkReimportResult(
    val sessionsReimported: Int,
    val results: Map<ApiSessionId, ApiReimportResult>,
)

/**
 * Re-imports every already-imported session of [credentialId], oldest first. Each session's delete
 * and rerun happen back to back rather than deleting every session's artifacts up front: the
 * un-exclusion check in [executeApiReimport] re-reads the live relationship state at the time it
 * runs, so a partner still reconciled against a not-yet-processed sibling session correctly stays
 * excluded until that session's turn — the ordering of delete vs. rerun across sessions doesn't
 * change the outcome, only the ordering of sessions relative to each other (oldest first, so an
 * earlier session's re-created transfers exist before a later session's own dedupe runs against them).
 */
@Suppress("LongParameterList")
suspend fun bulkReimportApiSessions(
    credentialId: ApiCredentialId,
    strategy: ApiImportStrategy,
    apiSessionRepository: ApiSessionReadRepository,
    accountRepository: AccountReadRepository,
    currencyRepository: CurrencyReadRepository,
    cryptoRepository: CryptoReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    transactionRepository: TransactionReadRepository,
    transferRelationshipRepository: TransferRelationshipReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    counterpartyAccountNames: Map<String, String> = emptyMap(),
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
): ApiBulkReimportResult {
    val importedSessionIds = apiSessionRepository.getImportedSessionRevisions().map { it.sessionId }.toSet()
    val sessions =
        apiSessionRepository
            .getSessionsByCredential(credentialId)
            .filter { it.id in importedSessionIds }
            .sortedBy { it.createdAt }

    val results = mutableMapOf<ApiSessionId, ApiReimportResult>()
    for ((index, session) in sessions.withIndex()) {
        onProgress?.invoke(
            ImportProgress(
                "Re-importing session #${session.id}",
                fraction = index.toFloat() / sessions.size,
                processed = index,
                total = sessions.size,
            ),
        )
        val plan = planApiReimport(session.id, apiSessionRepository)
        results[session.id] =
            executeApiReimport(
                plan = plan,
                session = session,
                strategy = strategy,
                apiSessionRepository = apiSessionRepository,
                accountRepository = accountRepository,
                currencyRepository = currencyRepository,
                cryptoRepository = cryptoRepository,
                accountAttributeRepository = accountAttributeRepository,
                transactionRepository = transactionRepository,
                transferRelationshipRepository = transferRelationshipRepository,
                tradeRepository = tradeRepository,
                maintenance = maintenance,
                importEngine = importEngine,
                counterpartyAccountNames = counterpartyAccountNames,
                passThroughAccounts = passThroughAccounts,
                refreshViews = false,
            )
    }
    maintenance.refreshMaterializedViews()
    return ApiBulkReimportResult(sessionsReimported = sessions.size, results = results)
}
