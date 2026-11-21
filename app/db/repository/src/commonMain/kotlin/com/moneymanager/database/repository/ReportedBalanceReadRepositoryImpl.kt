package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.database.mapper.AssetRowMapper
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.reconciliation.ReportedBalance
import com.moneymanager.domain.model.reconciliation.ReportedBalanceId
import com.moneymanager.domain.model.reconciliation.ReportedBalanceSeries
import com.moneymanager.domain.repository.ReportedBalanceReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

class ReportedBalanceReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ReportedBalanceReadRepository {
    private val queries = database.reportedBalanceSelectQueries
    private val sources = EntitySourceReader(database)

    override fun getSeries(): Flow<List<ReportedBalanceSeries>> =
        queries
            .selectAccountAssets()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows ->
                rows.map {
                    ReportedBalanceSeries(
                        accountId = AccountId(it.account_id),
                        accountName = it.account_name,
                        asset =
                            AssetRowMapper.buildAsset(
                                it.asset_id,
                                it.asset_code,
                                it.asset_name,
                                it.asset_scale_factor,
                                it.asset_fraction_digits,
                                it.asset_kind,
                            ),
                        count = it.balance_count,
                        latest = Instant.fromEpochMilliseconds(it.latest_timestamp),
                    )
                }
            }

    override suspend fun getBalances(
        accountId: AccountId,
        asset: Asset,
    ): List<ReportedBalance> =
        withContext(coroutineContext) {
            queries.selectBalances(accountId.id, asset.id.id).awaitAsList().map { row ->
                ReportedBalance(
                    id = ReportedBalanceId(row.id),
                    timestamp = Instant.fromEpochMilliseconds(row.timestamp),
                    amount = Money(BigInteger(row.amount), asset),
                    revisionId = row.revision_id,
                )
            }
        }

    override suspend fun getTransactionBalanceTimes(
        accountId: AccountId,
        asset: Asset,
    ): Map<Long, Instant> =
        withContext(coroutineContext) {
            queries
                .selectTransactionBalanceTimes(accountId.id, asset.id.id)
                .awaitAsList()
                .mapNotNull { row -> row.balance_timestamp?.let { row.transaction_id to Instant.fromEpochMilliseconds(it) } }
                .toMap()
        }

    override suspend fun getSources(id: ReportedBalanceId): List<SourceRecord> =
        withContext(coroutineContext) { sources.sourcesFor(EntityType.REPORTED_BALANCE, id.id) }
}
