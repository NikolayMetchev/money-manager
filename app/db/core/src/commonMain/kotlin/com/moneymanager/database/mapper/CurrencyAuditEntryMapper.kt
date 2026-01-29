@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForCurrency
import com.moneymanager.domain.model.CurrencyAuditEntry
import tech.mappie.api.ObjectMappie

object CurrencyAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForCurrency, CurrencyAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForCurrency): CurrencyAuditEntry =
        mapping {
            CurrencyAuditEntry::auditId fromValue from.id
            CurrencyAuditEntry::currencyId fromValue toCurrencyId(from.currency_id)
        }
}
