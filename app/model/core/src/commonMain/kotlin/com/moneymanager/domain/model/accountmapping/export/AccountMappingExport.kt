package com.moneymanager.domain.model.accountmapping.export

import com.moneymanager.domain.model.serialization.SortedListSerializer
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
    @Serializable(with = SortedAccountMappingListSerializer::class)
    val mappings: List<AccountMappingExport> = emptyList(),
)

/**
 * Serializes account-mapping lists sorted by [AccountMappingExport]'s natural order, so the artifact
 * bytes don't depend on per-device database-id order. Safe because runtime first-match resolution uses
 * each device's own id order, never the artifact's list order.
 */
object SortedAccountMappingListSerializer : SortedListSerializer<AccountMappingExport>(AccountMappingExport.serializer())

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
    val valuePattern: String,
    val accountName: String,
) : Comparable<AccountMappingExport> {
    override fun compareTo(other: AccountMappingExport): Int = compareValuesBy(this, other, { it.valuePattern }, { it.accountName })
}
