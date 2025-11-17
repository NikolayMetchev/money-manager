package com.moneymanager.data.mapper

import com.moneymanager.domain.model.Transaction
import com.moneymanager.domain.model.TransactionType
import kotlinx.datetime.Instant
import tech.mappie.api.ObjectMappie

object TransactionMapper : ObjectMappie<com.moneymanager.database.TransactionRecord, Transaction>() {
    override fun map(from: com.moneymanager.database.TransactionRecord) = mapping {
        Transaction::type fromValue TransactionType.valueOf(from.type)
        Transaction::transactionDate fromValue Instant.fromEpochMilliseconds(from.transactionDate)
        Transaction::createdAt fromValue Instant.fromEpochMilliseconds(from.createdAt)
        Transaction::updatedAt fromValue Instant.fromEpochMilliseconds(from.updatedAt)
    }
}
