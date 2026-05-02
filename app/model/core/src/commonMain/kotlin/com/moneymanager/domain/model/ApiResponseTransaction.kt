package com.moneymanager.domain.model

/**
 * Represents the import state of a single transaction entry found in an API response.
 * Allows the UI to display which transactions were imported, which were duplicates,
 * and which encountered errors.
 *
 * [transactionId] meaning depends on [state]:
 * - IMPORTED: the newly created Money Manager transaction ID
 * - DUPLICATE: the pre-existing transaction ID that this entry duplicates
 * - ERROR: null
 */
data class ApiResponseTransaction(
    val id: ApiResponseTransactionId,
    val responseId: ApiResponseId,
    val jsonPath: JsonPath,
    val state: ApiResponseTransactionState,
    val transactionId: TransferId?,
    val errorMessage: String?,
)

@JvmInline
value class ApiResponseTransactionId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

enum class ApiResponseTransactionState(
    val id: Int,
) {
    IMPORTED(1),
    DUPLICATE(2),
    ERROR(3),
    ;

    companion object {
        fun fromId(id: Int): ApiResponseTransactionState =
            entries.firstOrNull { it.id == id }
                ?: error("Unknown ApiResponseTransactionState id: $id")
    }
}
