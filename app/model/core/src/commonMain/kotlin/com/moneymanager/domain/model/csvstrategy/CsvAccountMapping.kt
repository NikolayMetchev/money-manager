@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.AccountId
import kotlin.time.Instant

/**
 * Persisted mapping that routes CSV column values matching a regex pattern
 * to a specific account ID during import.
 *
 * Applied BEFORE name lookup, allowing users to:
 * 1. Handle renamed accounts (original CSV name -> renamed account)
 * 2. Consolidate variations to a single account (e.g., "Paxos Technology LTD" -> "Paxos" account)
 *
 * @property id Unique identifier for this mapping
 * @property strategyId The import strategy this mapping belongs to
 * @property columnName The CSV column to match against (e.g., "Name", "Payee")
 * @property valuePattern Regex pattern for matching column values (case-insensitive by default)
 * @property accountId Target account when pattern matches
 * @property createdAt When this mapping was created
 * @property updatedAt When this mapping was last modified
 */
data class CsvAccountMapping(
    val id: Long,
    val strategyId: CsvImportStrategyId,
    val columnName: String,
    val valuePattern: Regex,
    val accountId: AccountId,
    val createdAt: Instant,
    val updatedAt: Instant,
)
