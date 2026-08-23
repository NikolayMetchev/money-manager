package com.moneymanager.database.mapper

import com.moneymanager.database.sql.audit.SelectAuditHistoryForCrypto
import com.moneymanager.domain.model.CryptoAuditEntry
import tech.mappie.api.ObjectMappie

object CryptoAuditEntryMapper :
    ObjectMappie<SelectAuditHistoryForCrypto, CryptoAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForCrypto): CryptoAuditEntry =
        mapping {
        }
}
