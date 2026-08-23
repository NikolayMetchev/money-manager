package com.moneymanager.database.mapper

import com.moneymanager.database.sql.audit.SelectAuditHistoryForCategory
import com.moneymanager.domain.model.CategoryAuditEntry
import tech.mappie.api.ObjectMappie

object CategoryAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForCategory, CategoryAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForCategory): CategoryAuditEntry =
        mapping {
        }
}
