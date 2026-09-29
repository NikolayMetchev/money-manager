package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.database.mapper.AssetRowMapper
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

class ReconciliationReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val csvImportStrategyRepository: CsvImportStrategyReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ReconciliationReadRepository {
    private val queries = database.reconciliationSelectQueries

    override fun getSources(): Flow<List<ReconciliationSource>> =
        csvImportStrategyRepository.getAllStrategies().map { strategies ->
            strategies
                .mapNotNull { strategy -> strategy.config.reconciliation?.let { it to strategy.name } }
                .groupBy { it.first.sourceName }
                .map { (source, entries) ->
                    ReconciliationSource(
                        name = source,
                        strategyNames = entries.map { it.second }.sorted(),
                        linkableAccountPrefix = entries.firstNotNullOfOrNull { it.first.linkableAccountPrefix },
                    )
                }.sortedBy { it.name }
        }

    override fun getShadowAccounts(): Flow<List<ShadowAccount>> =
        queries
            .selectShadowAccounts()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows -> rows.map { ShadowAccount(AccountId(it.id), it.name, it.source_name) } }

    override fun getLinks(): Flow<List<ReconciliationLink>> =
        queries
            .selectLinks()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows -> rows.map { ReconciliationLink(AccountId(it.shadow_account_id), AccountId(it.real_account_id)) } }

    override suspend fun getLegs(accountIds: Collection<AccountId>): List<ReconciliationLeg> {
        if (accountIds.isEmpty()) return emptyList()
        return withContext(coroutineContext) {
            // Each id is bound four times (one per UNION branch), so chunk to stay under the variable limit.
            accountIds
                .asSequence()
                .map { it.id }
                .distinct()
                .chunked(MAX_IDS_PER_QUERY / 4)
                .flatMap { chunk ->
                    queries.selectLegsForAccounts(chunk).executeAsList()
                }.sortedWith(compareBy({ it.timestamp }, { it.id }))
                .map { row ->
                    val asset =
                        AssetRowMapper.buildAsset(row.asset_id, row.asset_code, row.asset_name, row.asset_scale_factor, row.asset_kind)
                    val magnitude = BigInteger(row.amount)
                    ReconciliationLeg(
                        transactionId = row.id,
                        kind = if (row.is_trade == 1L) LegTransactionKind.TRADE else LegTransactionKind.TRANSFER,
                        timestamp = Instant.fromEpochMilliseconds(row.timestamp),
                        description = row.description,
                        accountId = AccountId(row.account_id),
                        counterpartyAccountId = row.counterparty_account_id?.let(::AccountId),
                        amount = Money(if (row.sign < 0) -magnitude else magnitude, asset),
                        isExcluded = row.is_excluded == 1L,
                    )
                }.toList()
        }
    }
}
