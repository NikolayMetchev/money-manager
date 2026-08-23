package com.moneymanager.database.mapper

import com.moneymanager.database.sql.audit.SelectOwnershipAuditHistoryForAccount
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import tech.mappie.api.ObjectMappie

object OwnershipAuditHistoryForAccountMapper :
    ObjectMappie<SelectOwnershipAuditHistoryForAccount, PersonAccountOwnershipAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectOwnershipAuditHistoryForAccount): PersonAccountOwnershipAuditEntry =
        mapping {
            PersonAccountOwnershipAuditEntry::personFullName fromValue
                buildPersonFullName(
                    from.person_first_name,
                    from.person_middle_name,
                    from.person_last_name,
                )
        }
}
