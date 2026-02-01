@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * Represents an audit entry for a person.
 * Captures the state of a person at a specific point in time during an INSERT, UPDATE, or DELETE operation.
 *
 * @property id Unique identifier for this audit entry
 * @property auditTimestamp When this audit entry was created
 * @property auditType The type of operation that triggered this audit (INSERT, UPDATE, DELETE)
 * @property personId The ID of the person that was audited
 * @property revisionId The revision of the person at the time of the audit
 * @property firstName The first name at the time of the audit
 * @property middleName The middle name at the time of the audit
 * @property lastName The last name at the time of the audit
 */
data class PersonAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val personId: PersonId,
    val revisionId: Long,
    val firstName: String,
    val middleName: String?,
    val lastName: String?,
    val source: EntitySource? = null,
) {
    val fullName: String
        get() =
            buildString {
                append(firstName)
                if (!middleName.isNullOrBlank()) {
                    append(" ")
                    append(middleName)
                }
                if (!lastName.isNullOrBlank()) {
                    append(" ")
                    append(lastName)
                }
            }
}
