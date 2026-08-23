package com.moneymanager.database.mapper

import com.moneymanager.database.sql.audit.SelectAuditHistoryForAccount
import com.moneymanager.domain.model.AccountAuditEntry
import tech.mappie.api.ObjectMappie

object AccountAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForAccount, AccountAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForAccount): AccountAuditEntry =
        mapping {
        }
}
