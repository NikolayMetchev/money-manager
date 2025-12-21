@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForTransferWithSource
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferAuditEntry
import tech.mappie.api.ObjectMappie
import kotlin.uuid.Uuid

object TransferAuditEntryWithSourceMapper :
    ObjectMappie<SelectAuditHistoryForTransferWithSource, TransferAuditEntry>(),
    IdConversions,
    InstantConversions,
    AuditTypeConversions {
    override fun map(from: SelectAuditHistoryForTransferWithSource): TransferAuditEntry =
        mapping {
            TransferAuditEntry::transferId fromValue toTransferId(from.id)
            TransferAuditEntry::amount fromValue Money(from.amount, from.toCurrency())
            TransferAuditEntry::source fromValue
                from.source_id?.let {
                    TransferSourceFromAuditMapper.map(from)
                }
        }
}

private fun SelectAuditHistoryForTransferWithSource.toCurrency(): Currency =
    Currency(
        id = CurrencyId(Uuid.parse(currency_id)),
        code = currency_code,
        name = currency_name,
        scaleFactor = currency_scaleFactor,
    )
