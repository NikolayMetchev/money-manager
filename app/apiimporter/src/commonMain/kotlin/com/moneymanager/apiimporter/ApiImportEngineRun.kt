package com.moneymanager.apiimporter

import co.touchlab.kermit.Logger
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransactionInsert
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.NamedAccount
import com.moneymanager.domain.model.rules.Record
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleAttribute
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.model.rules.RuleOutcome
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.AttributeSlot
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ExistingApiIdExtractor
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportConversion
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.bankKeysFrom
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.personalCounterpartyKey
import com.moneymanager.importengineapi.recordApiDownloadCoverage
import com.moneymanager.importengineapi.transactionIdAttributeName
import com.moneymanager.importengineapi.uniqueIdentifierAttributeNames
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiHttpResponse
import com.moneymanager.rest.ScaParams
import com.moneymanager.tasks.Progress
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
