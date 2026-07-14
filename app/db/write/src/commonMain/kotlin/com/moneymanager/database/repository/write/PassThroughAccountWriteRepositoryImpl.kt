package com.moneymanager.database.repository.write

import com.moneymanager.database.json.PassThroughRulesJsonCodec
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.write.PassThroughAccountWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class PassThroughAccountWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: PassThroughAccountReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : PassThroughAccountWriteRepository,
    PassThroughAccountReadRepository by reader {
    private val writeQueries = database.passThroughAccountWriteQueries

    override suspend fun create(account: PassThroughAccount): PassThroughAccountId =
        withContext(coroutineContext) {
            writeQueries.transactionWithResult {
                writeQueries.insert(
                    name = account.name,
                    conduit_account_name = account.conduitAccountName,
                    relationship_type_id = account.relationshipTypeId,
                    rules_json = PassThroughRulesJsonCodec.encode(account.rules),
                )
                PassThroughAccountId(writeQueries.lastInsertRowId().executeAsOne())
            }
        }

    override suspend fun update(account: PassThroughAccount): Unit =
        withContext(coroutineContext) {
            writeQueries.update(
                name = account.name,
                conduit_account_name = account.conduitAccountName,
                relationship_type_id = account.relationshipTypeId,
                rules_json = PassThroughRulesJsonCodec.encode(account.rules),
                id = account.id.value,
            )
        }

    override suspend fun delete(id: PassThroughAccountId): Unit =
        withContext(coroutineContext) {
            writeQueries.deleteById(id.value)
        }
}
