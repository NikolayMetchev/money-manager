package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsOne
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
                )
                val id = writeQueries.lastInsertRowId().awaitAsOne()
                insertRules(id, account)
                PassThroughAccountId(id)
            }
        }

    override suspend fun update(account: PassThroughAccount): Unit =
        withContext(coroutineContext) {
            writeQueries.transaction {
                writeQueries.update(
                    name = account.name,
                    conduit_account_name = account.conduitAccountName,
                    relationship_type_id = account.relationshipTypeId,
                    id = account.id.value,
                )
                writeQueries.deleteRules(account.id.value)
                insertRules(account.id.value, account)
            }
        }

    override suspend fun delete(id: PassThroughAccountId): Unit =
        withContext(coroutineContext) {
            writeQueries.deleteById(id.value)
        }

    private suspend fun insertRules(
        id: Long,
        account: PassThroughAccount,
    ) {
        account.rules.forEachIndexed { position, rule ->
            writeQueries.insertRule(
                pass_through_account_id = id,
                position = position.toLong(),
                detection_pattern = rule.detectionPattern,
                merchant_pattern = rule.merchantPattern,
                merchant_template = rule.merchantTemplate,
            )
        }
    }
}
