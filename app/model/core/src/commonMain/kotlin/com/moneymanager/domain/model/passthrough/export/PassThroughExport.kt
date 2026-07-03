package com.moneymanager.domain.model.passthrough.export

import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.passthrough.PassThroughRule
import kotlinx.serialization.Serializable

/**
 * Portable JSON artifact for one pass-through (conduit) account definition, used for file-based
 * export/import and the shared strategy library on remote storage. Carries no entity references —
 * [conduitAccountName] is a plain account name resolved/created on import, so applying an export never
 * needs reference resolution.
 */
@Serializable
data class PassThroughExport(
    /** App version the artifact was exported under; blanked when hashing for semantic comparison. */
    val version: String = "",
    val name: String,
    val conduitAccountName: String,
    val relationshipTypeId: Long = WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID,
    val rules: List<PassThroughRule> = emptyList(),
)
