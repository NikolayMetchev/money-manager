package com.moneymanager.domain.model.accountmapping.export

import kotlinx.serialization.Serializable

/**
 * Portable export bundle for the global set of account mappings.
 * Uses account names instead of database IDs for cross-device portability.
 *
 * @property version App version that created this export (for compatibility tracking)
 * @property mappings The exported account mappings
 */
@Serializable
data class AccountMappingsExport(
    val version: String,
    val mappings: List<AccountMappingExport> = emptyList(),
)

/**
 * Portable export format for a single persisted account mapping.
 *
 * @property valuePattern Regex pattern string from AccountMapping.valuePattern.
 * Consumers compile this with Regex(...), so it must be a valid regex with any
 * literal characters escaped as needed.
 * @property accountName Name of the target account (resolved to an id on import).
 */
@Serializable
data class AccountMappingExport(
    val columnName: String,
    val valuePattern: String,
    val accountName: String,
)
