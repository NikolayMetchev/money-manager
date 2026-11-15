package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.ImportGroupId
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.MaterializedTransaction
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationFacts
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.repository.ObservationReadRepository
import kotlin.time.Instant

class ObservationReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : ObservationReadRepository {
    private val queries = database.observationSelectQueries

    override suspend fun getObservation(id: ObservationId): Observation? =
        queries.selectHeaderById(id.id, ::Header).awaitAsOneOrNull()?.let { load(it) }

    override suspend fun getObservationsOf(owner: ObservationOwner): List<Observation> =
        queries
            .selectHeadersByOwner(owner.kindName(), owner.ownerId(), ::Header)
            .awaitAsList()
            .map { load(it) }

    override suspend fun getObservationsOfTransaction(transactionId: TransactionId): List<Observation> =
        queries
            .selectHeadersByTransaction(transactionId.id, ::Header)
            .awaitAsList()
            .map { load(it) }

    override suspend fun getMaterialized(transactionId: TransactionId): MaterializedTransaction? =
        when (transactionId) {
            is TransferId -> {
                queries
                    .selectMaterializedTransfer(transactionId.id) { timestamp, description, source, target, asset, amount ->
                        ObservedValues.Transfer(
                            timestamp = Instant.fromEpochMilliseconds(timestamp),
                            description = description,
                            sourceAccountId = AccountId(source),
                            targetAccountId = AccountId(target),
                            assetId = ObservedAssetId(asset),
                            amount = BigInteger(amount),
                        )
                    }.awaitAsOneOrNull()
                    ?.let { values ->
                        val attributes =
                            queries
                                .selectMaterializedTransferAttributes(transactionId.id) { typeId, groupKey, value ->
                                    ObservationAttribute(AttributeTypeId(typeId), groupKey, value)
                                }.awaitAsList()
                        MaterializedTransaction(values, attributes.toSet())
                    }
            }

            else -> {
                queries
                    .selectMaterializedTrade(
                        transactionId.id,
                    ) { timestamp, description, from, fromAsset, fromAmount, to, toAsset, toAmount ->
                        ObservedValues.Trade(
                            timestamp = Instant.fromEpochMilliseconds(timestamp),
                            description = description,
                            fromAccountId = AccountId(from),
                            fromAssetId = ObservedAssetId(fromAsset),
                            fromAmount = BigInteger(fromAmount),
                            toAccountId = AccountId(to),
                            toAssetId = ObservedAssetId(toAsset),
                            toAmount = BigInteger(toAmount),
                        )
                    }.awaitAsOneOrNull()
                    ?.let { values ->
                        val attributes =
                            queries
                                .selectMaterializedTradeAttributes(transactionId.id) { typeId, value ->
                                    ObservationAttribute(AttributeTypeId(typeId), "", value)
                                }.awaitAsList()
                        MaterializedTransaction(values, attributes.toSet())
                    }
            }
        }

    override suspend fun getAccountsWithObservations(accountIds: Collection<AccountId>): Set<AccountId> =
        if (accountIds.isEmpty()) {
            emptySet()
        } else {
            queries.selectAccountsWithObservations(accountIds.map { it.id }).awaitAsList().mapTo(mutableSetOf(), ::AccountId)
        }

    private class Header(
        val id: Long,
        val transactionId: Long,
        val kind: String,
        val ownerKind: String,
        val ownerId: Long,
        val slot: String,
    )

    private suspend fun load(header: Header): Observation {
        val id = header.id
        val kind = TransactionKind.valueOf(header.kind)
        val values =
            when (kind) {
                TransactionKind.TRANSFER -> {
                    queries
                        .selectTransferValues(id) { _, timestamp, description, source, target, asset, amount ->
                            ObservedValues.Transfer(
                                timestamp = Instant.fromEpochMilliseconds(timestamp),
                                description = description,
                                sourceAccountId = AccountId(source),
                                targetAccountId = AccountId(target),
                                assetId = ObservedAssetId(asset),
                                amount = BigInteger(amount),
                            )
                        }.awaitAsOne()
                }

                TransactionKind.TRADE -> {
                    queries
                        .selectTradeValues(id) { _, timestamp, description, from, fromAsset, fromAmount, to, toAsset, toAmount ->
                            ObservedValues.Trade(
                                timestamp = Instant.fromEpochMilliseconds(timestamp),
                                description = description,
                                fromAccountId = AccountId(from),
                                fromAssetId = ObservedAssetId(fromAsset),
                                fromAmount = BigInteger(fromAmount),
                                toAccountId = AccountId(to),
                                toAssetId = ObservedAssetId(toAsset),
                                toAmount = BigInteger(toAmount),
                            )
                        }.awaitAsOne()
                }
            }
        val attributes =
            queries
                .selectAttributes(id) { typeId, groupKey, value -> ObservationAttribute(AttributeTypeId(typeId), groupKey, value) }
                .awaitAsList()
                .toSet()
        val facts =
            ObservationFacts(
                approximateTime = queries.countApproximateTime(id).awaitAsOne() > 0,
                placeholderEnds = queries.selectPlaceholderEnds(id).awaitAsList().mapTo(LinkedHashSet(), ObservationEnd::fromDbName),
                grossAmount = queries.countGrossAmount(id).awaitAsOne() > 0,
            )
        return Observation(
            id = ObservationId(id),
            owner = ownerOf(header.ownerKind, header.ownerId),
            slot = header.slot,
            transactionId = transactionIdOf(kind, header.transactionId),
            content = ObservationContent(values, attributes, facts),
        )
    }
}

/** The `observation_owner` view's owner_kind for this owner. */
internal fun ObservationOwner.kindName(): String =
    when (this) {
        is ObservationOwner.Record -> "RECORD"
        is ObservationOwner.Group -> "GROUP"
        is ObservationOwner.Manual -> "MANUAL"
    }

internal fun ObservationOwner.ownerId(): Long =
    when (this) {
        is ObservationOwner.Record -> recordId.id
        is ObservationOwner.Group -> groupId.id
        is ObservationOwner.Manual -> entitySourceId
    }

private fun ownerOf(
    kind: String,
    id: Long,
): ObservationOwner =
    when (kind) {
        "RECORD" -> ObservationOwner.Record(ImportRecordId(id))
        "GROUP" -> ObservationOwner.Group(ImportGroupId(id))
        "MANUAL" -> ObservationOwner.Manual(id)
        else -> error("unknown observation owner kind $kind")
    }

/** A transaction id typed by the kind of transaction it is. */
fun transactionIdOf(
    kind: TransactionKind,
    id: Long,
): TransactionId =
    when (kind) {
        TransactionKind.TRANSFER -> TransferId(id)
        TransactionKind.TRADE -> TradeId(id)
    }
