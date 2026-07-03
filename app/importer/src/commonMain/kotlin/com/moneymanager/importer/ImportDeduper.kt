@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.StringSimilarity
import kotlin.time.Duration

/**
 * Information about an existing transfer, used by [ImportDeduper] for duplicate detection.
 *
 * @property attributes Existing attribute values keyed by type id (for identical-comparison).
 * @property uniqueKey The transfer's unique-identifier values (builder-defined keys), or empty when
 *   the dedupe policy does not use unique identifiers.
 */
data class ExistingTransferInfo(
    val transferId: TransferId,
    val transfer: Transfer,
    val attributes: Map<AttributeTypeId, String> = emptyMap(),
    val uniqueKey: Map<String, String> = emptyMap(),
    val apiId: String? = null,
)

/**
 * Classification of an [ImportTransfer] against existing transfers.
 *
 * @property existing The matched existing (database) transfer id, for DUPLICATE/UPDATED against the DB.
 * @property inBatchMatchIndex For an in-batch DUPLICATE (it matched an earlier accepted transfer in the
 *   same batch that has no id yet), the index of that earlier transfer in the classified list; the
 *   engine resolves it to the earlier transfer's created id. Null otherwise.
 * @property reversalLinks For a pass-through row that reverses an earlier movement (a
 *   refund/cancellation, or a re-booking that undoes one), the spend legs it reverses, keyed by this
 *   row's spend-leg index within its conduit chain. Resolved by the engine after classification; the
 *   deduper never sets it.
 */
data class Classified(
    val transfer: ImportTransfer,
    val status: ImportStatus,
    val existing: TransferId?,
    val inBatchMatchIndex: Int? = null,
    val reversalLinks: Map<Int, ReversalLink> = emptyMap(),
)

/**
 * A `reversal` relationship to create from a new pass-through spend leg (id1) to the spend leg it
 * reverses (id2).
 *
 * @property target The reversed spend leg: an existing (persisted) transfer, or a spend leg of an
 *   earlier row in the same to-import list (index into that list + leg index within that row's chain),
 *   resolved to its temp id at write time.
 * @property typeId The `reversal` relationship type id.
 */
data class ReversalLink(
    val target: ReversalTarget,
    val typeId: RelationshipTypeId,
)

/** See [ReversalLink.target]. */
sealed interface ReversalTarget {
    data class Existing(
        val id: TransferId,
    ) : ReversalTarget

    data class BatchRow(
        val toImportIndex: Int,
        val legIndex: Int,
    ) : ReversalTarget
}

/**
 * Deduplicates incoming transfers against existing ones according to a [DedupePolicy]. Account
 * references on the incoming transfers must already be resolved to [AccountRef.Existing] (the engine
 * does this before calling [classify]).
 *
 * Behaviour mirrors the previous CSV/API dedupe logic:
 *  - [DedupePolicy.UniqueIdentifier]: match by [ImportTransfer.uniqueKey]; also dedupes within the
 *    same batch (a later transfer whose key was already accepted in this batch is a DUPLICATE).
 *  - [DedupePolicy.FuzzyAllFields]: exact core+attribute match, then fuzzy match; existing-only.
 *  - [DedupePolicy.None]: everything IMPORTED.
 */
class ImportDeduper(
    private val policy: DedupePolicy,
    existing: List<ExistingTransferInfo>,
) {
    private val existingList = existing

    private val existingByUniqueKey: Map<Map<String, String>, ExistingTransferInfo> =
        existing.filter { it.uniqueKey.isNotEmpty() }.associateBy { it.uniqueKey }

    /** Unique keys accepted (IMPORTED) earlier in this batch, for in-batch dedupe. */
    private val seenInBatch = mutableSetOf<Map<String, String>>()

    // For ApiMultiKey: existing-DB indexes (-> real id) plus running in-batch indexes (-> classified
    // index of the accepted earlier transfer, resolved to its created id by the engine).
    private val existingApiId: Map<String, TransferId> =
        existing.mapNotNull { info -> info.apiId?.let { it to info.transferId } }.toMap()
    private val existingUniqueKeyId: Map<Map<String, String>, TransferId> =
        existing.filter { it.uniqueKey.isNotEmpty() }.associate { it.uniqueKey to it.transferId }
    private val existingMatchCandidates: List<Pair<TransferId, Transfer>> =
        existing.map { it.transferId to it.transfer }

    // Reconciliation only considers existing transfers this provider cannot identify by its own id
    // (apiId == null) — i.e. transfers from a different source — so genuine repeats from the same
    // provider (which carry an apiId) are never reconciled away.
    private val reconcileCandidates: List<Pair<TransferId, Transfer>> =
        existing.filter { it.apiId == null }.map { it.transferId to it.transfer }
    private val batchApiId = mutableMapOf<String, Int>()
    private val batchUniqueKey = mutableMapOf<Map<String, String>, Int>()
    private val batchMatchCandidates = mutableListOf<Pair<Int, Transfer>>()

    fun classify(transfers: List<ImportTransfer>): List<Classified> =
        transfers.mapIndexed { index, transfer -> classifyOne(index, transfer) }

    private fun classifyOne(
        index: Int,
        transfer: ImportTransfer,
    ): Classified =
        when (policy) {
            is DedupePolicy.None -> Classified(transfer, ImportStatus.IMPORTED, null)
            is DedupePolicy.UniqueIdentifier -> classifyByUniqueId(transfer)
            is DedupePolicy.FuzzyAllFields -> classifyByAllFields(transfer, policy)
            is DedupePolicy.ApiMultiKey -> classifyByApiMultiKey(index, transfer)
        }

    private fun classifyByApiMultiKey(
        index: Int,
        transfer: ImportTransfer,
    ): Classified {
        // Match an existing DB transfer first (yields its real id) ...
        val existingId =
            transfer.apiId?.let { existingApiId[it] }
                ?: transfer.uniqueKey?.takeIf { it.isNotEmpty() }?.let { existingUniqueKeyId[it] }
                ?: existingMatchCandidates.firstOrNull { (_, e) -> apiMatches(transfer, e) }?.first
        if (existingId != null) return Classified(transfer, ImportStatus.DUPLICATE, existingId)

        // Cross-source reconciliation: the same real movement seen from another provider. Keep this
        // record but tag it excluded-and-linked so the movement is counted once (see ApiMultiKey docs).
        classifyAsReconciled(transfer)?.let { return it }

        // ... then an earlier accepted transfer in this same batch (resolved to its created id later).
        val batchMatchIndex =
            transfer.apiId?.let { batchApiId[it] }
                ?: transfer.uniqueKey?.takeIf { it.isNotEmpty() }?.let { batchUniqueKey[it] }
                ?: batchMatchCandidates.firstOrNull { (_, e) -> apiMatches(transfer, e) }?.first
        if (batchMatchIndex != null) {
            return Classified(transfer, ImportStatus.DUPLICATE, existing = null, inBatchMatchIndex = batchMatchIndex)
        }

        // Accepted: register this transfer so later items in the batch dedupe against it.
        transfer.apiId?.let { batchApiId[it] = index }
        transfer.uniqueKey?.takeIf { it.isNotEmpty() }?.let { batchUniqueKey[it] = index }
        batchMatchCandidates += index to transfer.toComparableTransfer(TransferId(0))
        return Classified(transfer, ImportStatus.IMPORTED, null)
    }

    /**
     * Classifies [transfer] as a cross-source reconciliation of an existing transfer when the policy
     * enables it and a same source+target+amount transfer from a different source exists within the
     * reconcile window. The returned transfer carries a plain exclusion attribute (value "reconciled")
     * so it is kept but excluded from balance totals, plus a `reconciled` relationship linking it
     * (id1) to the existing transfer (id2). Null when reconciliation is disabled or no match is found.
     */
    private fun classifyAsReconciled(transfer: ImportTransfer): Classified? {
        val apiPolicy = policy as? DedupePolicy.ApiMultiKey ?: return null
        val window = apiPolicy.reconcileWindow ?: return null
        val exclusionTypeId = apiPolicy.reconciledExclusionAttributeTypeId ?: return null
        val relationshipTypeId = apiPolicy.reconciledRelationshipTypeId ?: return null
        val matchId =
            reconcileCandidates.firstOrNull { (_, existing) -> reconcileMatches(transfer, existing, window) }?.first
                ?: return null
        val attributes =
            if (transfer.attributes.any { it.typeId == exclusionTypeId }) {
                transfer.attributes
            } else {
                transfer.attributes + NewAttribute(exclusionTypeId, "reconciled")
            }
        val relationships = transfer.relationships + NewRelationship(relatedTransferId = matchId, typeId = relationshipTypeId)
        return Classified(
            // Drop any fee: a cross-source duplicate's fee is itself a duplicate, so it must not be
            // re-created (it would double-count) — the original source's fee already covers it.
            transfer.copy(attributes = attributes, relationships = relationships, fee = null),
            ImportStatus.IMPORTED,
            existing = null,
        )
    }

    private fun reconcileMatches(
        transfer: ImportTransfer,
        existing: Transfer,
        window: Duration,
    ): Boolean {
        if (transfer.amount != existing.amount) return false
        if (transfer.fromAccount.requireId() != existing.sourceAccountId) return false
        if (transfer.toAccount.requireId() != existing.targetAccountId) return false
        val delta = (requireNotNull(transfer.timestamp) - existing.timestamp).absoluteValue
        return delta <= window
    }

    private fun apiMatches(
        transfer: ImportTransfer,
        existing: Transfer,
    ): Boolean {
        if (transfer.timestamp != existing.timestamp || transfer.amount != existing.amount) return false
        val source = transfer.fromAccount.requireId()
        val target = transfer.toAccount.requireId()
        return (source == existing.sourceAccountId && target == existing.targetAccountId) ||
            (source == existing.targetAccountId && target == existing.sourceAccountId)
    }

    private fun ImportTransfer.toComparableTransfer(id: TransferId): Transfer =
        Transfer(
            id = id,
            timestamp = requireNotNull(timestamp),
            description = description,
            sourceAccountId = fromAccount.requireId(),
            targetAccountId = toAccount.requireId(),
            amount = requireNotNull(amount),
        )

    private fun classifyByUniqueId(transfer: ImportTransfer): Classified {
        val key = transfer.uniqueKey
        if (key.isNullOrEmpty()) return Classified(transfer, ImportStatus.IMPORTED, null)

        val existing = existingByUniqueKey[key]
        if (existing != null) {
            val status =
                if (transfersAreIdentical(transfer, existing)) ImportStatus.DUPLICATE else ImportStatus.UPDATED
            return Classified(transfer, status, existing.transferId)
        }

        // Not in the database; check whether an earlier transfer in this same batch already claimed it.
        if (!seenInBatch.add(key)) {
            return Classified(transfer, ImportStatus.DUPLICATE, null)
        }
        return Classified(transfer, ImportStatus.IMPORTED, null)
    }

    private fun classifyByAllFields(
        transfer: ImportTransfer,
        policy: DedupePolicy.FuzzyAllFields,
    ): Classified {
        // First pass: an exact core-field match preserves the DUPLICATE/UPDATED distinction.
        for (existing in existingList) {
            if (coreFieldsMatch(transfer, existing.transfer)) {
                val status =
                    if (attributesAreIdentical(transfer, existing)) ImportStatus.DUPLICATE else ImportStatus.UPDATED
                return Classified(transfer, status, existing.transferId)
            }
        }
        // Second pass: tolerate bank re-export drift (close date, similar description).
        for (existing in existingList) {
            if (isFuzzyDuplicate(transfer, existing.transfer, policy)) {
                return Classified(transfer, ImportStatus.DUPLICATE, existing.transferId)
            }
        }
        return Classified(transfer, ImportStatus.IMPORTED, null)
    }

    private fun coreFieldsMatch(
        transfer: ImportTransfer,
        existing: Transfer,
    ): Boolean =
        transfer.timestamp == existing.timestamp &&
            transfer.fromAccount.requireId() == existing.sourceAccountId &&
            transfer.toAccount.requireId() == existing.targetAccountId &&
            transfer.amount == existing.amount &&
            transfer.description == existing.description

    private fun transfersAreIdentical(
        transfer: ImportTransfer,
        existing: ExistingTransferInfo,
    ): Boolean = coreFieldsMatch(transfer, existing.transfer) && attributesAreIdentical(transfer, existing)

    private fun attributesAreIdentical(
        transfer: ImportTransfer,
        existing: ExistingTransferInfo,
    ): Boolean {
        val newAttrs = transfer.attributes.associate { it.typeId to it.value }
        return newAttrs == existing.attributes
    }

    private fun isFuzzyDuplicate(
        transfer: ImportTransfer,
        existing: Transfer,
        policy: DedupePolicy.FuzzyAllFields,
    ): Boolean {
        if (transfer.amount != existing.amount) return false
        val sharesAccount =
            transfer.fromAccount.requireId() == existing.sourceAccountId ||
                transfer.toAccount.requireId() == existing.targetAccountId
        if (!sharesAccount) return false
        val withinDateTolerance =
            (requireNotNull(transfer.timestamp) - existing.timestamp).absoluteValue <= policy.dateTolerance
        if (!withinDateTolerance) return false
        return StringSimilarity.similarity(transfer.description, existing.description) >= policy.similarityThreshold
    }

    // Dedupe runs on resolved CREATE transfers, whose fields are present; null indicates a builder error.
    private fun AccountRef?.requireId(): AccountId =
        when (this) {
            is AccountRef.Existing -> id
            is AccountRef.Local ->
                error("ImportDeduper requires resolved account references; got unresolved $key")
            null -> error("ImportDeduper requires a resolved account reference; got null")
        }
}
