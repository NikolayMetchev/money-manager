package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.coroutines.runCatchingCancellable
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.ownedAttributeValues
import com.moneymanager.domain.model.csvstrategy.uniqueIdentifierAttributeTypes
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.ColumnRecord
import com.moneymanager.domain.model.rules.EXCLUDED_ATTRIBUTE_TYPE_NAME
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.BatchRelationship
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.createAccountMappings
import com.moneymanager.importengineapi.createAccounts
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.deleteEmptyImportCreatedAccounts
import com.moneymanager.importengineapi.feeAccounts
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.tradeDedupePolicy
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.WeightedProgress
import com.moneymanager.xlsx.createXlsxParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.withTag("CsvLegLinking")

/** Links each debit leg to the nearest credit leg of the same key within [window] (credits may be shared). */
internal fun pairDebitsToCredits(
    legs: List<CsvTransferWithAttributes>,
    window: Duration,
    relationshipTypeName: String,
): List<Pair<Long, BatchRelationship>> {
    val credits = legs.filter { it.groupLeg!!.side == GroupLegSide.CREDIT }
    return legs
        .filter { it.groupLeg!!.side == GroupLegSide.DEBIT }
        .mapNotNull { debit ->
            val match =
                credits
                    .filter { it.groupLeg!!.key == debit.groupLeg!!.key }
                    .minByOrNull { (it.transfer.timestamp - debit.transfer.timestamp).absoluteValue }
                    ?.takeIf { (it.transfer.timestamp - debit.transfer.timestamp).absoluteValue <= window }
                    ?: return@mapNotNull null
            debit.rowIndex to BatchRelationship(ImportRowKey.CsvRow(match.rowIndex), relationshipTypeName)
        }
}

/**
 * Links every member leg to the anchor leg that shares its key, as the relationship's primary member. A leg with a blank
 * key (the row names no group) links to nothing, and an anchor never links to itself.
 */
internal fun linkMembersToAnchors(
    legs: List<CsvTransferWithAttributes>,
    relationshipTypeName: String,
): List<Pair<Long, BatchRelationship>> {
    val (anchors, members) = legs.filter { it.groupLeg!!.key.isNotBlank() }.partition { it.groupLeg!!.funding }
    val anchorByKey = anchors.sortedBy { it.rowIndex }.groupBy { it.groupLeg!!.key }.mapValues { (_, group) -> group.first() }
    return members.mapNotNull { member ->
        val anchor = anchorByKey[member.groupLeg!!.key] ?: return@mapNotNull null
        member.rowIndex to BatchRelationship(ImportRowKey.CsvRow(anchor.rowIndex), relationshipTypeName)
    }
}

/**
 * Links each funding leg (from the funding leg) to the nearest unclaimed non-funding leg on the other side
 * with the same key within [window], one to one. Legs the source excluded (a refused deposit) record no
 * movement, so they pair with nothing.
 */
internal fun pairFundingLegs(
    legs: List<CsvTransferWithAttributes>,
    window: Duration,
    relationshipTypeName: String,
): List<Pair<Long, BatchRelationship>> {
    val counted = legs.filter { leg -> leg.attributes.none { (name, _) -> name == EXCLUDED_ATTRIBUTE_TYPE_NAME } }
    val (funding, others) = counted.partition { it.groupLeg!!.funding }
    val claimed = mutableSetOf<Long>()
    return funding.sortedBy { it.transfer.timestamp }.mapNotNull { leg ->
        val match =
            others
                .filter {
                    it.rowIndex !in claimed &&
                        it.groupLeg!!.key == leg.groupLeg!!.key &&
                        it.groupLeg.side != leg.groupLeg.side &&
                        (it.transfer.timestamp - leg.transfer.timestamp).absoluteValue <= window
                }.minByOrNull { (it.transfer.timestamp - leg.transfer.timestamp).absoluteValue }
                ?: return@mapNotNull null
        claimed += match.rowIndex
        leg.rowIndex to BatchRelationship(ImportRowKey.CsvRow(match.rowIndex), relationshipTypeName)
    }
}
