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
            AccountAuditEntry::auditId fromValue from.id
            AccountAuditEntry::accountId fromValue toAccountId(from.account_id)
            AccountAuditEntry::openingDate fromValue toInstant(from.opening_date)
            AccountAuditEntry::categoryName fromValue from.category_name
        }
}
