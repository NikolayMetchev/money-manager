@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForPersonAccountOwnership
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import tech.mappie.api.ObjectMappie

object PersonAccountOwnershipAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForPersonAccountOwnership, PersonAccountOwnershipAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForPersonAccountOwnership): PersonAccountOwnershipAuditEntry =
        mapping {
            PersonAccountOwnershipAuditEntry::auditId fromValue from.id
            PersonAccountOwnershipAuditEntry::ownershipId fromValue from.ownership_id
            PersonAccountOwnershipAuditEntry::personId fromValue toPersonId(from.person_id)
            PersonAccountOwnershipAuditEntry::accountId fromValue toAccountId(from.account_id)
            PersonAccountOwnershipAuditEntry::personFullName fromValue
                buildPersonFullName(
                    from.person_first_name,
                    from.person_middle_name,
                    from.person_last_name,
                )
            PersonAccountOwnershipAuditEntry::accountName fromValue from.account_name
        }
}

private fun buildPersonFullName(
    firstName: String?,
    middleName: String?,
    lastName: String?,
): String? {
    if (firstName == null) return null
    return buildString {
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
