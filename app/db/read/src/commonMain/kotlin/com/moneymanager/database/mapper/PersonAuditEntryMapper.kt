package com.moneymanager.database.mapper

import com.moneymanager.database.sql.audit.SelectAuditHistoryForPerson
import com.moneymanager.domain.model.PersonAuditEntry
import tech.mappie.api.ObjectMappie

object PersonAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForPerson, PersonAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForPerson): PersonAuditEntry =
        mapping {
        }
}
