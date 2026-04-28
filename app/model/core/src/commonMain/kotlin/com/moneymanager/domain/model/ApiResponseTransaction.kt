package com.moneymanager.domain.model

/**
 * Represents the import state of a single transaction entry found in an API response.
 * Allows the UI to display which transactions were imported, which were duplicates,
 * and which encountered errors.
 */
data class ApiResponseTransaction(
    val id: ApiResponseTransactionId,
    val responseId: ApiResponseId,
    val jsonPath: String,
    val state: ApiResponseTransactionState,
    /** The Money Manager transaction ID for IMPORTED and DUPLICATE entries. */
    val transactionId: Long?,
    /** For DUPLICATE entries: the ID of the already-imported transaction. */
    val duplicateOfTransactionId: Long?,
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
