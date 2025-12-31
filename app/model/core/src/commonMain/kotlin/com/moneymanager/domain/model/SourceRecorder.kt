@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

/**
 * Strategy for recording transfer source information.
 * Implementations live in app/db/core and call TransferSourceQueries directly.
 */
interface SourceRecorder {
    fun insert(transfer: Transfer)
}
