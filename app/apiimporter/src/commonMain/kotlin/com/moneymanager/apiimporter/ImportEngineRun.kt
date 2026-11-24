package com.moneymanager.apiimporter

import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.ExistingApiIdExtractor
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.tasks.Progress

/**
 * Assembles the single [ImportBatch] from the prepared transfers + the resolver's account/people/ownership
 * intents and runs the engine exactly once. The engine performs every write, including the API
 * bookkeeping (the response-transaction records, via an [ImportBatch] session mutation).
 */
internal suspend fun runImportEngine(
    setup: ImportSetup,
    prepared: PreparedTransfers,
): ImportResult {
    val orderedPrepared = prepared.orderedPrepared
    val transactionIdAttributeName = setup.transactionIdAttributeName

    val importTransfers =
        orderedPrepared.map { p ->
            ImportTransfer(
                rowKey = ImportRowKey.ApiJsonPath(p.requestId, p.item.jsonPath.value),
                fromAccount = p.source,
                toAccount = p.target,
                source = Source.Api(setup.sessionId),
                statementAccount = p.ownAccount,
                timestamp = p.timestamp,
                description = p.description,
                amount = p.amount,
                attributes = p.attributes,
                uniqueKey = p.uniqueKey,
                apiId = p.transactionApiId,
                excludedFromBalances = !p.item.declineReason.isNullOrBlank(),
                fee = p.fee,
                passThrough = p.passThrough,
                conversion = p.conversion,
            )
        }

    // An own account the accounts endpoint returned but that ended up on no prepared transfer leg (e.g.
    // Monzo's read-only `uk_rewards` pseudo-account, whose feed is always empty) is never created.
    // Counterparty accounts are never candidates: only keys ensureSourceAccounts allocated can be pruned.
    val referencedAccountKeys = orderedPrepared.flatMapTo(mutableSetOf()) { it.referencedLocalAccountKeys() }
    val prunedAccountKeys = setup.accountResolver.pruneUnreferencedSourceAccounts(referencedAccountKeys)
    val ownershipIntents =
        setup.peopleResolver.ownershipIntents().filterNot { ownership ->
            (ownership.account as? AccountRef.Local)?.key in prunedAccountKeys
        }

    val uniqueIdTxFields = setup.uniqueAttributeNames
    val batch =
        ImportBatch(
            transfers = importTransfers,
            reportedBalances =
                orderedPrepared.mapNotNull { p ->
                    p.reportedBalance?.let {
                        ImportReportedBalance(
                            record = ImportRecordKey.ApiItem(setup.sessionId, p.requestId, p.item.jsonPath.value),
                            account = p.ownAccount,
                            timestamp = p.item.balanceAt ?: p.timestamp,
                            amount = it,
                        )
                    }
                },
            dedupePolicy =
                setup.strategy.dedupePolicy(
                    setup.attributeTypeCache.getOrCreate(WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME),
                ),
            accountsToCreate = setup.accountResolver.intents(),
            peopleToCreate = setup.peopleResolver.intents(),
            ownerships = ownershipIntents,
            apiIdExtractor =
                ExistingApiIdExtractor { transfer ->
                    transactionIdAttributeName?.let { name ->
                        transfer.attributes.firstOrNull { it.attributeType.name == name }?.value
                    }
                },
            uniqueKeyExtractor =
                if (uniqueIdTxFields.isEmpty()) {
                    null
                } else {
                    ExistingUniqueKeyExtractor { transfer ->
                        val key =
                            uniqueIdTxFields.associateWith { fieldName ->
                                transfer.attributes.firstOrNull { it.attributeType.name == fieldName }?.value
                            }
                        // Skip existing transfers missing any unique field (matches indexByUniqueTransactionFields).
                        if (key.values.any { it == null }) null else key.mapValues { it.value!! }
                    }
                },
        )

    val importResult =
        setup.importEngine.import(
            batch = batch,
            onProgress = { progress ->
                setup.onProgress(
                    Progress(
                        detail = progress.detail,
                        fraction = progress.fraction?.let { 0.7f + (it * 0.25f) },
                    ),
                )
            },
        )

    // Map the engine's per-transfer outcomes (aligned to orderedPrepared) into response records.
    val responseRecords = prepared.failedRecords.toMutableList()
    orderedPrepared.forEachIndexed { index, p ->
        val outcome = importResult.orderedRowOutcomes[index]
        val state =
            when (outcome.status) {
                ImportStatus.IMPORTED -> ApiResponseTransactionState.IMPORTED
                else -> ApiResponseTransactionState.DUPLICATE
            }
        responseRecords +=
            p.responseRecord(state)
    }

    // Clear any response-transaction rows from a prior run of this session and insert the fresh set in
    // the same batch — otherwise a re-import (e.g. after a strategy edit bumps the revision) violates
    // the (response_id, json_path) unique index, and a cancellation between two separate top-level
    // import() calls could leave the session's response-transaction provenance deleted but not replaced.
    setup.importEngine.import(
        ImportBatch(
            apiSessionMutations =
                listOf(
                    ApiSessionMutation.DeleteResponseTransactionsBySession(setup.sessionId),
                    ApiSessionMutation.InsertResponseTransactions(
                        responseRecords
                            .sortedWith(compareBy<ResponseTransactionImportRecord> { it.pageIndex }.thenBy { it.itemIndex })
                            .map { it.toInsert() },
                    ),
                ),
        ),
    )

    return importResult
}
