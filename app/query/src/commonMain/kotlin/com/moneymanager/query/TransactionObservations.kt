package com.moneymanager.query

import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.observation.TransferField
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportRecordReadRepository
import com.moneymanager.domain.repository.ObservationReadRepository
import com.moneymanager.domain.repository.PersonOverrideReadRepository
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/** The repositories that say what a transaction is made of: its observations, and what a person decided about it. */
class ObservationReads(
    val observationRepository: ObservationReadRepository,
    val personOverrideRepository: PersonOverrideReadRepository,
    val importRecordRepository: ImportRecordReadRepository,
    val csvImportRepository: CsvImportReadRepository,
    val attributeTypeRepository: AttributeTypeReadRepository,
    val currencyRepository: CurrencyReadRepository,
    val cryptoRepository: CryptoReadRepository,
)

/** What one transaction is made of: who observed it, and what a person pinned, split and overrode on it. */
data class TransactionObservations(
    val observations: List<DetailObservation> = emptyList(),
    /** "These are different" decisions naming one of [observations]; the other observation is on another transaction. */
    val splits: List<DetailSplit> = emptyList(),
    /** The values a person set on the transaction, which win over every observation. */
    val overrides: List<DetailOverride> = emptyList(),
)

/** One owner's report of one slot of the transaction. */
data class DetailObservation(
    val id: ObservationId,
    val owner: ObservationOwner,
    /** Where the owner is, for a person: a file and row, an API item, a group of rows, or "by hand". */
    val ownerLabel: String,
    val slot: String,
    val timestamp: Instant,
    val description: String,
    /** What this observation says moved, in the assets it names. */
    val legs: List<TransactionLeg>,
    /** A person pinned it here: it stays on this transaction whatever the matcher's rules say. */
    val pinned: Boolean,
)

data class DetailSplit(
    /** The observation of this transaction the split names. */
    val observation: ObservationId,
    val other: ObservationId,
    /** Where [other] is now, or null when it no longer exists. */
    val otherTransaction: TransactionId?,
)

/** A value a person set; [value] is null when they removed an attribute. */
data class DetailOverride(
    val name: String,
    val value: String?,
)

/** Reads [TransactionObservations] for [transactionId]; [knownAssets] saves loading the assets its legs already hold. */
internal suspend fun ObservationReads.observationsOf(
    transactionId: TransactionId,
    knownAssets: Collection<Asset>,
): TransactionObservations {
    val observations = observationRepository.getObservationsOfTransaction(transactionId)
    val ids = observations.map { it.id }
    val pinned =
        personOverrideRepository
            .getPinsOnTransactions(listOf(transactionId))
            .mapTo(mutableSetOf()) { it.observationId }
    val own = ids.toSet()
    val splits =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            personOverrideRepository.getSplits(ids).map { split ->
                val (mine, other) = if (split.first in own) split.first to split.second else split.second to split.first
                DetailSplit(mine, other, observationRepository.getObservation(other)?.transactionId)
            }
        }
    val labels = ownerLabels(observations.map { it.owner }.toSet())
    val assets = assetsById(observations, knownAssets)
    return TransactionObservations(
        observations =
            observations.map { observation ->
                val values = observation.content.values
                DetailObservation(
                    id = observation.id,
                    owner = observation.owner,
                    ownerLabel = labels.getValue(observation.owner),
                    slot = observation.slot,
                    timestamp = values.timestamp(),
                    description = values.description(),
                    legs = values.legs(assets),
                    pinned = observation.id in pinned,
                )
            },
        splits = splits.sortedWith(compareBy({ it.observation.id }, { it.other.id })),
        overrides = overridesOf(transactionId),
    )
}

private suspend fun ObservationReads.overridesOf(transactionId: TransactionId): List<DetailOverride> {
    val attributes =
        when (transactionId) {
            is TransferId -> personOverrideRepository.getTransferAttributeOverrides(transactionId)
            is TradeId -> personOverrideRepository.getTradeAttributeOverrides(transactionId)
            else -> emptyList()
        }
    val fields = (transactionId as? TransferId)?.let { personOverrideRepository.getTransferOverrides(it) }.orEmpty()
    val typeNames: Map<AttributeTypeId, String> =
        if (attributes.isEmpty()) emptyMap() else attributeTypeRepository.getAll().first().associate { it.id to it.name }
    return fields.entries.sortedBy { it.key.ordinal }.map { (field, value) -> DetailOverride(field.label(), value) } +
        attributes.map { override ->
            val name = typeNames[override.typeId] ?: "attribute ${override.typeId.id}"
            val label = if (override.groupKey.isEmpty()) name else "$name [${override.groupKey}]"
            DetailOverride(label, (override as? AttributeOverride.Set)?.value)
        }
}

private fun TransferField.label(): String = name.lowercase().replace('_', ' ')

private suspend fun ObservationReads.ownerLabels(owners: Set<ObservationOwner>): Map<ObservationOwner, String> {
    if (owners.isEmpty()) return emptyMap()
    val records =
        importRecordRepository
            .getRecords(owners.filterIsInstance<ObservationOwner.Record>().map { it.recordId })
            .associateBy { it.id }
    val groups =
        importRecordRepository
            .getGroupsById(owners.filterIsInstance<ObservationOwner.Group>().map { it.groupId })
            .associateBy { it.id }
    val fileNames = mutableMapOf<CsvImportId, String?>()

    suspend fun fileName(id: CsvImportId): String =
        fileNames.getOrPut(id) { csvImportRepository.getImport(id).first()?.originalFileName } ?: "file $id"

    return owners.associateWith { owner ->
        when (owner) {
            is ObservationOwner.Record -> {
                when (val key = records[owner.recordId]?.key) {
                    is ImportRecordKey.CsvRow -> "${fileName(key.csvImportId)} row ${key.rowIndex}"
                    is ImportRecordKey.ApiItem -> "API session ${key.sessionId} item ${key.jsonPath}"
                    null -> "record ${owner.recordId}"
                }
            }

            is ObservationOwner.Group -> {
                groups[owner.groupId]?.key?.let { "${it.groupName} ${it.keyValue}" } ?: "group ${owner.groupId}"
            }

            is ObservationOwner.Manual -> {
                "by hand"
            }
        }
    }
}

private suspend fun ObservationReads.assetsById(
    observations: List<Observation>,
    known: Collection<Asset>,
): Map<Long, Asset> {
    val byId = known.associateBy { it.id.id }
    val wanted = observations.flatMap { it.content.values.assetIds() }.toSet()
    if (byId.keys.containsAll(wanted)) return byId
    val all = currencyRepository.getAllCurrencies().first() + cryptoRepository.getAllCryptoAssets().first()
    return byId + all.filter { it.id.id in wanted }.associateBy { it.id.id }
}

private fun ObservedValues.assetIds(): List<Long> =
    when (this) {
        is ObservedValues.Transfer -> listOf(assetId.id)
        is ObservedValues.Trade -> listOf(fromAssetId.id, toAssetId.id)
    }

private fun ObservedValues.timestamp(): Instant =
    when (this) {
        is ObservedValues.Transfer -> timestamp
        is ObservedValues.Trade -> timestamp
    }

private fun ObservedValues.description(): String =
    when (this) {
        is ObservedValues.Transfer -> description
        is ObservedValues.Trade -> description
    }

private fun ObservedValues.legs(assets: Map<Long, Asset>): List<TransactionLeg> =
    when (this) {
        is ObservedValues.Transfer -> {
            listOfNotNull(assets[assetId.id]?.let { TransactionLeg(sourceAccountId, targetAccountId, Money(amount, it)) })
        }

        is ObservedValues.Trade -> {
            listOfNotNull(
                assets[fromAssetId.id]?.let { TransactionLeg(fromAccountId, toAccountId, Money(fromAmount, it)) },
                assets[toAssetId.id]?.let { TransactionLeg(toAccountId, fromAccountId, Money(toAmount, it)) },
            )
        }
    }
