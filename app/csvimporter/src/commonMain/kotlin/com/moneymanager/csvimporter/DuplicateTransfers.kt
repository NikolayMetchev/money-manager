package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.uniqueIdentifierAttributeTypes
import com.moneymanager.importengineapi.DedupePolicy

internal class DuplicateTransfers(
    config: CsvStrategyConfig<*>,
    private val existingTransfers: List<ExistingTransferInfo>,
) {
    private val fuzzy =
        config.duplicateDateTolerance?.let { DedupePolicy.FuzzyAllFields(dateTolerance = it.duration) } ?: DedupePolicy.FuzzyAllFields()
    private val uniqueIdentifierTypes: List<String> = config.uniqueIdentifierAttributeTypes()

    private val existingTransfersByUniqueId: Map<Map<String, String>, ExistingTransferInfo> =
        if (uniqueIdentifierTypes.isNotEmpty()) {
            existingTransfers.associateBy { it.uniqueIdentifierValues }
        } else {
            emptyMap()
        }

    fun classify(
        transfer: Transfer,
        attributes: List<Pair<String, String>>,
    ): Pair<ImportStatus, TransferId?> {
        val existing =
            if (uniqueIdentifierTypes.isEmpty()) {
                existingTransfers.firstOrNull { sameFields(transfer, it.transfer) }
            } else {
                val key = uniqueIdentifierTypes.associateWith { type -> attributes.firstOrNull { it.first == type }?.second.orEmpty() }
                existingTransfersByUniqueId[key]
            }
        if (existing != null) {
            val identical = sameFields(transfer, existing.transfer) && attributes.toMap() == existing.attributes.toMap()
            return (if (identical) ImportStatus.DUPLICATE else ImportStatus.UPDATED) to existing.transferId
        }
        if (uniqueIdentifierTypes.isEmpty()) {
            val match =
                existingTransfers.firstOrNull { existing ->
                    fuzzy.matches(
                        existing.transfer,
                        transfer.sourceAccountId,
                        transfer.targetAccountId,
                        transfer.amount,
                        transfer.timestamp,
                        transfer.description,
                    )
                }
            if (match != null) return ImportStatus.DUPLICATE to match.transferId
        }
        return ImportStatus.IMPORTED to null
    }

    private fun sameFields(
        a: Transfer,
        b: Transfer,
    ): Boolean =
        a.timestamp == b.timestamp && a.description == b.description && a.sourceAccountId == b.sourceAccountId &&
            a.targetAccountId == b.targetAccountId && a.amount == b.amount
}
