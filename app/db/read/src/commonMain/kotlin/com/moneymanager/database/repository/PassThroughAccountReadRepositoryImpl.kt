package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.json.PassThroughRulesJsonCodec
import com.moneymanager.database.sql.passThroughAccount.Pass_through_account
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

class PassThroughAccountReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : PassThroughAccountReadRepository {
    private val selectQueries = database.passThroughAccountSelectQueries

    override fun getAll(): Flow<List<PassThroughAccount>> =
        selectQueries
            .selectAll()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows -> rows.map(::toDomain) }

    override fun getById(id: PassThroughAccountId): Flow<PassThroughAccount?> =
        selectQueries
            .selectById(id.value)
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it?.let(::toDomain) }

    private fun toDomain(entity: Pass_through_account): PassThroughAccount =
        PassThroughAccount(
            id = PassThroughAccountId(entity.id),
            name = entity.name,
            conduitAccountName = entity.conduit_account_name,
            relationshipTypeId = entity.relationship_type_id,
            rules = PassThroughRulesJsonCodec.decode(entity.rules_json),
        )
}
