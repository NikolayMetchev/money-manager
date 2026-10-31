package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import com.moneymanager.database.sql.passThroughAccount.Pass_through_account
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.model.passthrough.PassThroughRule
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

class PassThroughAccountReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : PassThroughAccountReadRepository {
    private val selectQueries = database.passThroughAccountSelectQueries

    // An account's rules are only ever written together with it, so a change to the account table is a change to any.
    // They're read in one transaction, so an account is never paired with another write's rules.
    override fun getAll(): Flow<List<PassThroughAccount>> =
        selectQueries
            .selectAll()
            .asFlow()
            .map {
                selectQueries.transactionWithResult {
                    val rows = selectQueries.selectAll().awaitAsList()
                    val rules = rulesByAccount()
                    rows.map { toDomain(it, rules) }
                }
            }.flowOn(coroutineContext)

    override fun getById(id: PassThroughAccountId): Flow<PassThroughAccount?> =
        selectQueries
            .selectById(id.value)
            .asFlow()
            .map {
                selectQueries.transactionWithResult {
                    selectQueries.selectById(id.value).awaitAsOneOrNull()?.let { toDomain(it, rulesByAccount()) }
                }
            }.flowOn(coroutineContext)

    private suspend fun rulesByAccount(): Map<Long, List<PassThroughRule>> =
        selectQueries.selectAllRules().awaitAsList().groupBy(
            { it.pass_through_account_id },
            { PassThroughRule(it.detection_pattern, it.merchant_pattern, it.merchant_template) },
        )

    private fun toDomain(
        entity: Pass_through_account,
        rules: Map<Long, List<PassThroughRule>>,
    ): PassThroughAccount =
        PassThroughAccount(
            id = PassThroughAccountId(entity.id),
            name = entity.name,
            conduitAccountName = entity.conduit_account_name,
            relationshipTypeId = entity.relationship_type_id,
            rules = rules[entity.id].orEmpty(),
        )
}
