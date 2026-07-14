package com.moneymanager.domain.model

/**
 * A row to be recorded against an API response. Lives in the model (not alongside the write repository)
 * because the DB-free import engine builds these declaratively before any write repository is involved.
 */
data class ApiResponseTransactionInsert(
    val responseId: ApiResponseId,
    val jsonPath: JsonPath,
    val state: ApiResponseTransactionState,
    val transactionId: TransferId?,
    val errorMessage: String?,
)
