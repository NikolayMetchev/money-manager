@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForAccount
import com.moneymanager.domain.model.AccountAuditEntry
import tech.mappie.api.ObjectMappie

object AccountAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForAccount, AccountAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForAccount): AccountAuditEntry =
        mapping {
            AccountAuditEntry::accountId fromValue toAccountId(from.id)
            AccountAuditEntry::openingDate fromValue toInstant(from.opening_date)
            AccountAuditEntry::categoryName fromValue from.category_name
        }
}
