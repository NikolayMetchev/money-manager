package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.EntityType
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
import com.moneymanager.domain.model.observation.VacantObservation
import com.moneymanager.domain.repository.ObservationReadRepository
import com.moneymanager.domain.repository.ObservedRecordSources
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ObservationReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : ObservationReadRepository {
    private val queries = database.observationSelectQueries
    private val entitySources = database.entitySourceSelectQueries

    override suspend fun getObservation(id: ObservationId): Observation? =
        queries.selectHeaderById(id.id, ::Header).awaitAsOneOrNull()?.let { load(listOf(it)).single() }

    override suspend fun getObservationsOf(owner: ObservationOwner): List<Observation> =
        load(
            queries
                .selectHeadersByOwner(owner.kindName(), owner.ownerId(), ::Header)
                .awaitAsList(),
        )

    override suspend fun getObservationsOfRecords(recordIds: Collection<ImportRecordId>): List<Observation> =
        queryInChunks(recordIds.map { it.id }, MAX_IDS_PER_QUERY) { ids ->
            load(queries.selectHeadersByRecords(ids, ::Header).awaitAsList())
        }

    override suspend fun getReservedIds(ids: Collection<ObservationId>): Set<ObservationId> =
        queryInChunks(ids.map { it.id }, MAX_IDS_PER_QUERY) { chunk ->
            queries.selectReservedIds(chunk).awaitAsList()
        }.mapTo(mutableSetOf(), ::ObservationId)

    override suspend fun getCreationSourceId(transactionId: TransactionId): Long? =
        entitySources
            .selectEntitySourceForRevision(
                if (transactionId is TransferId) EntityType.TRANSFER.id else EntityType.TRADE.id,
                transactionId.id,
                1L,
            ).awaitAsOneOrNull()
            ?.id

    override suspend fun getObservationsOfTransaction(transactionId: TransactionId): List<Observation> =
        getObservationsOfTransactions(listOf(transactionId))

    override suspend fun getObservationsOfTransactions(transactionIds: Collection<TransactionId>): List<Observation> =
        queryInChunks(transactionIds.map { it.id }, MAX_IDS_PER_QUERY) { ids ->
            load(queries.selectHeadersByTransactions(ids, ::Header).awaitAsList())
        }

    override suspend fun getRecordTransactions(
        csvImports: Set<CsvImportId>,
        apiSessions: Set<ApiSessionId>,
    ): Set<TransactionId> =
        buildSet {
            for (ids in csvImports.map { it.id.toString() }.chunked(MAX_IDS_PER_QUERY)) {
                addAll(queries.selectRecordTransactionsByCsvImports(ids, ::storedTransactionId).awaitAsList())
            }
            for (ids in apiSessions.map { it.id }.chunked(MAX_IDS_PER_QUERY)) {
                addAll(queries.selectRecordTransactionsByApiSessions(ids, ::storedTransactionId).awaitAsList())
            }
        }

    override suspend fun getRecordSourcesOnAccount(accountId: AccountId): ObservedRecordSources =
        ObservedRecordSources(
            csvImports =
                queries
                    .selectCsvImportsOnAccount(accountId.id)
                    .awaitAsList()
                    .mapTo(mutableSetOf()) { CsvImportId(Uuid.parse(it)) },
            apiSessions = queries.selectApiSessionsOnAccount(accountId.id).awaitAsList().mapTo(mutableSetOf(), ::ApiSessionId),
        )

    override suspend fun getTransactionsObservedByOtherRecords(
        transactions: Collection<TransactionId>,
        csvImports: Set<CsvImportId>,
        apiSessions: Set<ApiSessionId>,
    ): Set<TransactionId> {
        val csvIds = csvImports.mapTo(mutableSetOf()) { it.id.toString() }
        val apiIds = apiSessions.mapTo(mutableSetOf()) { it.id }
        return queryInChunks(transactions.map { it.id }, MAX_IDS_PER_QUERY) { ids ->
            queries.selectUnreservedRecordSourcesByTransactions(ids).awaitAsList().mapNotNull { row ->
                if (row.csv_import_id in csvIds || row.api_session_id in apiIds) null else storedTransactionId(row.transaction_id, row.kind)
            }
        }.toSet()
    }

    override suspend fun getObservationsOnAccounts(accountIds: Collection<AccountId>): List<Observation> =
        if (accountIds.isEmpty()) {
            emptyList()
        } else {
            loadOnAccounts(accountIds.map { it.id })
        }

    /**
     * Every observation naming one of [ids], in a fixed number of queries (#1433): a match scope can hold every
     * observation of the database, so select their details directly by account.
     */
    private suspend fun loadOnAccounts(ids: List<Long>): List<Observation> {
        val headers = queries.selectHeadersOnAccounts(ids, ::Header).awaitAsList()
        if (headers.isEmpty()) return emptyList()
        val values = mutableMapOf<Long, ObservedValues>()
        queries
            .selectTransferValuesOnAccounts(ids) { id, timestamp, description, source, target, asset, amount ->
                values[id] = transferValues(timestamp, description, source, target, asset, amount)
            }.awaitAsList()
        queries
            .selectTradeValuesOnAccounts(ids) { id, timestamp, description, from, fromAsset, fromAmount, to, toAsset, toAmount ->
                values[id] = tradeValues(timestamp, description, from, fromAsset, fromAmount, to, toAsset, toAmount)
            }.awaitAsList()
        val attributes =
            queries
                .selectAttributesOnAccounts(ids) { id, typeId, groupKey, value ->
                    id to
                        ObservationAttribute(AttributeTypeId(typeId), groupKey, value)
                }.awaitAsList()
                .groupBy({ it.first }, { it.second })
        val approximate = queries.selectApproximateTimeOnAccounts(ids).awaitAsList().toSet()
        val placeholders =
            queries
                .selectPlaceholderEndsOnAccounts(ids) { id, end -> id to ObservationEnd.fromDbName(end) }
                .awaitAsList()
                .groupBy({ it.first }, { it.second })
        val gross = queries.selectGrossAmountOnAccounts(ids).awaitAsList().toSet()
        val ownEnds =
            queries
                .selectOwnEndsOnAccounts(ids) { id, end -> id to ObservationEnd.fromDbName(end) }
                .awaitAsList()
                .toMap()
        return assemble(headers, values, attributes, approximate, placeholders, gross, ownEnds)
    }

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

    override suspend fun getMaterializedIds(transactionIds: Collection<TransactionId>): Set<TransactionId> =
        buildSet {
            val transfers = transactionIds.filterIsInstance<TransferId>().map { it.id }
            val trades = transactionIds.filterIsInstance<TradeId>().map { it.id }
            queryInChunks(transfers, MAX_IDS_PER_QUERY) { queries.selectMaterializedTransferIds(it).awaitAsList() }
                .mapTo(this, ::TransferId)
            queryInChunks(trades, MAX_IDS_PER_QUERY) { queries.selectMaterializedTradeIds(it).awaitAsList() }
                .mapTo(this, ::TradeId)
        }

    override suspend fun getAccountsWithObservations(accountIds: Collection<AccountId>): Set<AccountId> =
        if (accountIds.isEmpty()) {
            emptySet()
        } else {
            queries.selectAccountsWithObservations(accountIds.map { it.id }).awaitAsList().mapTo(mutableSetOf(), ::AccountId)
        }

    override suspend fun getVacantReserved(): List<VacantObservation> = queries.selectVacantReserved(::vacantObservation).awaitAsList()

    override suspend fun getVacantReservedRecords(recordIds: Collection<ImportRecordId>): List<VacantObservation> =
        queryInChunks(recordIds.map { it.id }, MAX_IDS_PER_QUERY) {
            queries.selectVacantReservedRecords(it, ::vacantObservation).awaitAsList()
        }

    override suspend fun getVacantReservedGroups(): List<VacantObservation> =
        queries.selectVacantReservedGroups(::vacantObservation).awaitAsList()

    override suspend fun getVacantTransactionIds(among: Collection<TransactionId>): Set<TransactionId> =
        queryInChunks(among.map { it.id }, MAX_IDS_PER_QUERY) { chunk ->
            queries.selectVacantTransactionIds(chunk) { id, kind -> storedTransactionId(id, kind) }.awaitAsList()
        }.toSet()

    override suspend fun getReserved(): List<Observation> = load(queries.selectReserved(::Header).awaitAsList())

    override suspend fun isReserved(id: ObservationId): Boolean = queries.isReserved(id.id).awaitAsOne() > 0

    private fun vacantObservation(
        id: Long,
        transactionId: Long,
        kind: String,
        ownerKind: String,
        ownerId: Long,
        slot: String,
    ): VacantObservation = VacantObservation(ObservationId(id), ownerOf(ownerKind, ownerId), slot, storedTransactionId(transactionId, kind))

    private class Header(
        val id: Long,
        val transactionId: Long,
        val kind: String,
        val ownerKind: String,
        val ownerId: Long,
        val slot: String,
    )

    private suspend fun load(headers: List<Header>): List<Observation> =
        headers.chunked(MAX_IDS_PER_QUERY).flatMap { chunk ->
            val ids = chunk.map { it.id }
            val values = mutableMapOf<Long, ObservedValues>()
            queries
                .selectTransferValues(ids) { id, timestamp, description, source, target, asset, amount ->
                    values[id] = transferValues(timestamp, description, source, target, asset, amount)
                }.awaitAsList()
            queries
                .selectTradeValues(ids) { id, timestamp, description, from, fromAsset, fromAmount, to, toAsset, toAmount ->
                    values[id] = tradeValues(timestamp, description, from, fromAsset, fromAmount, to, toAsset, toAmount)
                }.awaitAsList()
            val attributes =
                queries
                    .selectAttributes(ids) { id, typeId, groupKey, value ->
                        id to
                            ObservationAttribute(AttributeTypeId(typeId), groupKey, value)
                    }.awaitAsList()
                    .groupBy({ it.first }, { it.second })
            val approximate = queries.selectApproximateTime(ids).awaitAsList().toSet()
            val placeholders =
                queries
                    .selectPlaceholderEnds(ids) { id, end -> id to ObservationEnd.fromDbName(end) }
                    .awaitAsList()
                    .groupBy({ it.first }, { it.second })
            val gross = queries.selectGrossAmount(ids).awaitAsList().toSet()
            val ownEnds =
                queries
                    .selectOwnEnds(ids) { id, end -> id to ObservationEnd.fromDbName(end) }
                    .awaitAsList()
                    .toMap()
            assemble(chunk, values, attributes, approximate, placeholders, gross, ownEnds)
        }

    @Suppress("LongParameterList")
    private fun assemble(
        headers: List<Header>,
        values: Map<Long, ObservedValues>,
        attributes: Map<Long, List<ObservationAttribute>>,
        approximate: Set<Long>,
        placeholders: Map<Long, List<ObservationEnd>>,
        gross: Set<Long>,
        ownEnds: Map<Long, ObservationEnd>,
    ): List<Observation> =
        headers.map { header ->
            val id = header.id
            val kind = TransactionKind.valueOf(header.kind)
            Observation(
                id = ObservationId(id),
                owner = ownerOf(header.ownerKind, header.ownerId),
                slot = header.slot,
                transactionId = transactionIdOf(kind, header.transactionId),
                content =
                    ObservationContent(
                        values = checkNotNull(values[id]) { "observation $id has no $kind values" },
                        attributes = attributes[id].orEmpty().toSet(),
                        facts =
                            ObservationFacts(
                                approximateTime = id in approximate,
                                placeholderEnds = placeholders[id].orEmpty().toCollection(LinkedHashSet()),
                                grossAmount = id in gross,
                                ownEnd = ownEnds[id],
                            ),
                    ),
            )
        }
}

private fun storedTransactionId(
    id: Long,
    kind: String,
): TransactionId = transactionIdOf(TransactionKind.valueOf(kind), id)

@Suppress("LongParameterList")
private fun transferValues(
    timestamp: Long,
    description: String,
    source: Long,
    target: Long,
    asset: Long,
    amount: String,
) = ObservedValues.Transfer(
    timestamp = Instant.fromEpochMilliseconds(timestamp),
    description = description,
    sourceAccountId = AccountId(source),
    targetAccountId = AccountId(target),
    assetId = ObservedAssetId(asset),
    amount = BigInteger(amount),
)

@Suppress("LongParameterList")
private fun tradeValues(
    timestamp: Long,
    description: String,
    from: Long,
    fromAsset: Long,
    fromAmount: String,
    to: Long,
    toAsset: Long,
    toAmount: String,
) = ObservedValues.Trade(
    timestamp = Instant.fromEpochMilliseconds(timestamp),
    description = description,
    fromAccountId = AccountId(from),
    fromAssetId = ObservedAssetId(fromAsset),
    fromAmount = BigInteger(fromAmount),
    toAccountId = AccountId(to),
    toAssetId = ObservedAssetId(toAsset),
    toAmount = BigInteger(toAmount),
)

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
