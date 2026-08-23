package com.moneymanager.database.mapper

import com.moneymanager.database.sql.audit.SelectAuditHistoryForCurrency
import com.moneymanager.domain.model.CurrencyAuditEntry
import tech.mappie.api.ObjectMappie

object CurrencyAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForCurrency, CurrencyAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForCurrency): CurrencyAuditEntry =
        mapping {
        }
}
