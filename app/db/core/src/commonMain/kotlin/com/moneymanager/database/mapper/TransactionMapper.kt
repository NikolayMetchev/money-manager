@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.domain.model.Transaction
import tech.mappie.api.ObjectMappie
import kotlin.time.Instant

object TransactionMapper : ObjectMappie<com.moneymanager.database.sql.TransactionRecord, Transaction>() {
    override fun map(from: com.moneymanager.database.sql.TransactionRecord) =
        mapping {
            Transaction::timestamp fromValue Instant.fromEpochMilliseconds(from.timestamp)
        }
}
