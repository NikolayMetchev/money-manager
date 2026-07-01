@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model.accountmapping

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import kotlin.time.Instant

/**
 * Persisted mapping that routes CSV column values matching a regex pattern
 * to a specific account ID during import.
 *
 * A mapping is either global ([strategyId] == null; applies to every import strategy, CSV and QIF)
 * or scoped to a single CSV import strategy. During matching, an import running strategy S uses the
 * global mappings plus S's mappings; a strategy-specific match wins over a global one.
 *
 * Applied BEFORE name lookup, to consolidate variations to a single account
 * (e.g., "Paxos Technology LTD" -> "Paxos" account).
 *
 * @property id Unique identifier for this mapping
 * @property strategyId The strategy this mapping is scoped to, or null for a global mapping
 * @property columnName The CSV column to match against (e.g., "Name", "Payee")
 * @property valuePattern Regex pattern for matching column values (case-insensitive by default)
 * @property accountId Target account when pattern matches
 * @property createdAt When this mapping was created
 * @property updatedAt When this mapping was last modified
 */
data class AccountMapping(
    val id: Long,
    val strategyId: CsvImportStrategyId? = null,
    val columnName: String,
    val valuePattern: Regex,
    val accountId: AccountId,
    val createdAt: Instant,
    val updatedAt: Instant,
)
