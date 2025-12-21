@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.SelectAuditHistoryForTransfer
import com.moneymanager.database.sql.SelectAuditHistoryForTransferWithSource
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import kotlin.time.Instant.Companion.fromEpochMilliseconds
import kotlin.uuid.Uuid

object TransferAuditEntryMapper {
    fun map(from: SelectAuditHistoryForTransfer): TransferAuditEntry {
        val currency =
            Currency(
                id = CurrencyId(Uuid.parse(from.currency_id)),
                code = from.currency_code,
                name = from.currency_name,
                scaleFactor = from.currency_scaleFactor,
            )
        return TransferAuditEntry(
            auditId = from.auditId,
            auditTimestamp = fromEpochMilliseconds(from.auditTimestamp),
            auditType = enumValueOf<AuditType>(from.auditType.uppercase()),
            transferId = TransferId(Uuid.parse(from.id)),
            revisionId = from.revisionId,
            timestamp = fromEpochMilliseconds(from.timestamp),
            description = from.description,
            sourceAccountId = AccountId(from.sourceAccountId),
            targetAccountId = AccountId(from.targetAccountId),
            amount = Money(from.amount, currency),
        )
    }

    fun mapWithSource(from: SelectAuditHistoryForTransferWithSource): TransferAuditEntry {
        val currency =
            Currency(
                id = CurrencyId(Uuid.parse(from.currency_id)),
                code = from.currency_code,
                name = from.currency_name,
                scaleFactor = from.currency_scaleFactor,
            )
        val source =
            from.source_id?.let {
                TransferSourceMapper.mapFromAuditQuery(from)
            }
        return TransferAuditEntry(
            auditId = from.auditId,
            auditTimestamp = fromEpochMilliseconds(from.auditTimestamp),
            auditType = enumValueOf<AuditType>(from.auditType.uppercase()),
            transferId = TransferId(Uuid.parse(from.id)),
            revisionId = from.revisionId,
            timestamp = fromEpochMilliseconds(from.timestamp),
            description = from.description,
            sourceAccountId = AccountId(from.sourceAccountId),
            targetAccountId = AccountId(from.targetAccountId),
            amount = Money(from.amount, currency),
            source = source,
        )
    }

    fun mapList(list: List<SelectAuditHistoryForTransfer>): List<TransferAuditEntry> = list.map(::map)
}
