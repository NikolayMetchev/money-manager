package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.StringSimilarity
import kotlin.time.Instant

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
 * A `reversal` relationship to create from a new pass-through spend leg (primary) to the spend leg it
 * reverses (related).
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
 * Finds the rows of a batch that its own source already recorded, according to a [DedupePolicy]. Account
 * references on the incoming transfers must already be resolved to [AccountRef.Existing] (the engine
 * does this before calling [classify]). Another source's record of the same movement is never this
 * class's business: the matcher decides that over the stored observations (`MatchSync`).
 *
 *  - [DedupePolicy.UniqueIdentifier]: match by [ImportTransfer.uniqueKey]; also dedupes within the
 *    same batch (a later transfer whose key was already accepted in this batch is a DUPLICATE).
 *  - [DedupePolicy.FuzzyAllFields]: exact core+attribute match, then fuzzy match; existing-only.
 *  - [DedupePolicy.ApiMultiKey]: api id, unique key or exact time + amount + accounts, against the
 *    database and then earlier rows of the batch.
 *  - [DedupePolicy.None]: everything IMPORTED.
 */
class ImportDeduper(
    private val policy: DedupePolicy,
    existing: List<ExistingTransferInfo>,
    /**
     * Existing transfers the batch's own file created earlier. An excluded one may still be a row's
     * fuzzy duplicate: it is this file's earlier record of the row, which keeps its exclusion.
     */
    private val ownSourceLegs: Set<TransferId> = emptySet(),
) {
    /**
     * Exact-match key over a transfer's core fields. Incoming-side fields are nullable, so an
     * incomplete incoming transfer builds a key that simply misses every (fully populated) existing
     * entry — the same outcome as the field-by-field comparison it replaces.
     */
    private data class CoreKey(
        val timestamp: Instant?,
        val sourceAccountId: AccountId,
        val targetAccountId: AccountId,
        val amount: Money?,
        val description: String?,
    )

    private fun Transfer.coreKey() = CoreKey(timestamp, sourceAccountId, targetAccountId, amount, description)

    private fun ImportTransfer.coreKey() = CoreKey(timestamp, fromAccount.requireId(), toAccount.requireId(), amount, description)

    // Indexes over the existing transfers so classification is a hash lookup (plus a scan of the small
    // matching bucket) instead of a linear pass over the whole DB history per incoming row. Buckets
    // preserve input order, so first-match-in-list-order semantics are unchanged.
    private val existingByCoreKey: Map<CoreKey, List<ExistingTransferInfo>> =
        existing.groupBy { it.transfer.coreKey() }
    private val existingByAmount: Map<Money, List<ExistingTransferInfo>> =
        existing.groupBy { it.transfer.amount }

    // The reconciliation exclusion attribute type (when the policy enables cross-source reconciliation),
    // ignored in attribute comparison so a reconciled transfer still dedupes cleanly on re-import. UniqueIdentifier has always
    // compared the marker like any attribute; ignoring it there too would change which of its rows read UPDATED.
    private val reconciledExclusionTypeId: AttributeTypeId? =
        policy.reconciledExclusionAttributeTypeId.takeUnless { policy is DedupePolicy.UniqueIdentifier }

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

    // apiMatches needs an exact timestamp+amount, so bucket the candidates by that pair.
    private val existingMatchCandidates: Map<Pair<Instant, Money>, List<Pair<TransferId, Transfer>>> =
        existing
            .map { it.transferId to it.transfer }
            .groupBy { (_, t) -> t.timestamp to t.amount }

    /** Existing legs already excluded from balances: they count nowhere, so no row is their fuzzy duplicate. */
    private val existingExcludedLegs: Set<TransferId> =
        reconciledExclusionTypeId
            ?.let { typeId -> existing.filter { typeId in it.attributes }.mapTo(mutableSetOf()) { it.transferId } }
            .orEmpty()

    // Transfer id -> apiId, for the same non-conflict check applied to in-batch candidates below.
    private val existingApiIdByTransferId: Map<TransferId, String> =
        existing.mapNotNull { info -> info.apiId?.let { info.transferId to it } }.toMap()

    private val batchApiId = mutableMapOf<String, Int>()
    private val batchUniqueKey = mutableMapOf<Map<String, String>, Int>()

    /** A fuzzy-match candidate from earlier in this batch, keeping its own apiId (see [apiIdConflict]). */
    private data class BatchCandidate(
        val index: Int,
        val transfer: Transfer,
        val apiId: String?,
    )

    private val batchMatchCandidates = mutableListOf<BatchCandidate>()

    /** The batch row [classify] is on, so the fuzzy pass can leave a leg to a row that matches it exactly. */
    private var classifyingIndex = 0

    /**
     * Existing legs an earlier row of this batch already matched (exactly or fuzzily). A persisted leg
     * records ONE movement, so it can be the twin of at most one incoming row: without this, an export
     * that gives every deposit the same description ("GBP Deposit (via FPS)") loses a genuine second
     * £1,000 deposit hours later, because the fuzzy pass — which deliberately tolerates date drift —
     * matches it against the first one's leg as well.
     */
    private val matchedExistingIds = mutableSetOf<TransferId>()

    /**
     * Each existing leg some row of this batch matches exactly, with that row's index. The fuzzy pass leaves these
     * to their own row: a statement lists two like payments days apart newest first, so the later one would otherwise
     * take the earlier one's record before the earlier row reaches it, and the earlier row, finding its record gone,
     * would book the payment again.
     */
    private var exactRows: Map<TransferId, Int> = emptyMap()

    /** Whether existing leg [id] is not the one some other row of this batch matches exactly ([exactRows]). */
    private fun isFreeForThisRow(id: TransferId): Boolean = exactRows[id].let { it == null || it == classifyingIndex }

    fun classify(transfers: List<ImportTransfer>): List<Classified> {
        exactRows = exactMatchesByRow(transfers)
        return transfers.mapIndexed { index, transfer ->
            classifyingIndex = index
            when (policy) {
                is DedupePolicy.None -> Classified(transfer, ImportStatus.IMPORTED, null)
                is DedupePolicy.UniqueIdentifier -> classifyByUniqueId(transfer)
                is DedupePolicy.FuzzyAllFields -> classifyByAllFields(transfer, policy)
                is DedupePolicy.ApiMultiKey -> classifyByApiMultiKey(index, transfer)
            }
        }
    }

    private fun exactMatchesByRow(transfers: List<ImportTransfer>): Map<TransferId, Int> {
        if (policy !is DedupePolicy.FuzzyAllFields) return emptyMap()
        val rows = mutableMapOf<TransferId, Int>()
        transfers.forEachIndexed { index, transfer ->
            existingByCoreKey[transfer.coreKey()]
                ?.firstOrNull { it.transferId !in rows }
                ?.let { rows[it.transferId] = index }
        }
        return rows
    }

    private fun classifyByApiMultiKey(
        index: Int,
        transfer: ImportTransfer,
    ): Classified {
        // Match an existing DB transfer first (yields its real id) ...
        val existingId =
            transfer.apiId?.let { existingApiId[it] }
                ?: transfer.uniqueKey?.takeIf { it.isNotEmpty() }?.let { existingUniqueKeyId[it] }
                ?: apiMatchCandidatesFor(transfer)
                    .firstOrNull { (id, e) -> apiMatches(transfer, e) && !apiIdConflict(transfer.apiId, existingApiIdByTransferId[id]) }
                    ?.first
        if (existingId != null) return Classified(transfer, ImportStatus.DUPLICATE, existingId)

        // ... then an earlier accepted transfer in this same batch (resolved to its created id later).
        val batchMatchIndex =
            transfer.apiId?.let { batchApiId[it] }
                ?: transfer.uniqueKey?.takeIf { it.isNotEmpty() }?.let { batchUniqueKey[it] }
                ?: batchMatchCandidates
                    .firstOrNull { c -> apiMatches(transfer, c.transfer) && !apiIdConflict(transfer.apiId, c.apiId) }
                    ?.index
        if (batchMatchIndex != null) {
            return Classified(transfer, ImportStatus.DUPLICATE, existing = null, inBatchMatchIndex = batchMatchIndex)
        }

        // Accepted: register this transfer so later items in the batch dedupe against it.
        transfer.apiId?.let { batchApiId[it] = index }
        transfer.uniqueKey?.takeIf { it.isNotEmpty() }?.let { batchUniqueKey[it] = index }
        batchMatchCandidates += BatchCandidate(index, transfer.toComparableTransfer(TransferId(0)), transfer.apiId)
        return Classified(transfer, ImportStatus.IMPORTED, null)
    }

    /**
     * True when both sides carry a provider-native id and they differ — e.g. Kraken's Earn
     * autoallocation books a spot-debit and Earn-credit leg as two distinct ledger rows, same
     * timestamp/amount/account-pair but opposite direction; each has its own unique `ledger_id`. Fuzzy
     * (timestamp+amount+either-direction) matching exists only for legacy/no-apiId records, so it must
     * never treat two differently-identified real movements as the same one.
     */
    private fun apiIdConflict(
        incoming: String?,
        existing: String?,
    ): Boolean = incoming != null && existing != null && incoming != existing

    /** The existing transfers that could satisfy [apiMatches] (exact timestamp + amount bucket). */
    private fun apiMatchCandidatesFor(transfer: ImportTransfer): List<Pair<TransferId, Transfer>> {
        val timestamp = transfer.timestamp ?: return emptyList()
        val amount = transfer.amount ?: return emptyList()
        return existingMatchCandidates[timestamp to amount].orEmpty()
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
        if (!key.isNullOrEmpty()) {
            val existing = existingByUniqueKey[key]
            if (existing != null) {
                val status =
                    if (transfersAreIdentical(transfer, existing)) ImportStatus.DUPLICATE else ImportStatus.UPDATED
                return Classified(transfer, status, existing.transferId)
            }
            // Not in the database; an earlier transfer in this same batch may have already claimed this key.
            if (!seenInBatch.add(key)) {
                return Classified(transfer, ImportStatus.DUPLICATE, null)
            }
        }
        return Classified(transfer, ImportStatus.IMPORTED, null)
    }

    private fun classifyByAllFields(
        transfer: ImportTransfer,
        policy: DedupePolicy.FuzzyAllFields,
    ): Classified {
        // First pass: an exact core-field match preserves the DUPLICATE/UPDATED distinction.
        existingByCoreKey[transfer.coreKey()]
            ?.firstOrNull { it.transferId !in matchedExistingIds }
            ?.let { existing ->
                matchedExistingIds += existing.transferId
                val status =
                    if (attributesAreIdentical(transfer, existing)) ImportStatus.DUPLICATE else ImportStatus.UPDATED
                return Classified(transfer, status, existing.transferId)
            }
        // Second pass: tolerate bank re-export drift (close date, similar description) — the amount
        // must still match exactly, so only that bucket needs scanning. Of several equal candidates the
        // nearest in time is this row's record.
        transfer.amount?.let { amount ->
            val timestamp = requireNotNull(transfer.timestamp)
            existingByAmount[amount]
                ?.filter { existing ->
                    // An excluded leg counts nowhere, so a row dropped as its duplicate would count nowhere either —
                    // unless the leg is this same file's earlier record of the row, which keeps its exclusion.
                    existing.transferId !in matchedExistingIds &&
                        isFreeForThisRow(existing.transferId) &&
                        (existing.transferId !in existingExcludedLegs || existing.transferId in ownSourceLegs) &&
                        isFuzzyDuplicate(transfer, existing.transfer, policy)
                }?.minByOrNull { (it.transfer.timestamp - timestamp).absoluteValue }
                ?.let { existing ->
                    matchedExistingIds += existing.transferId
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
        // The cross-source reconciliation exclusion attribute is engine-added (never present in the
        // source), so an existing transfer that was reconciled carries it while the re-imported row does
        // not. Ignore it on both sides: a row that is otherwise unchanged is a DUPLICATE, not a perpetual
        // UPDATED, so re-importing the same export is idempotent.
        val ignore = reconciledExclusionTypeId
        val newAttrs = transfer.attributes.filter { it.typeId != ignore }.associate { it.typeId to it.value }
        val existingAttrs = if (ignore == null) existing.attributes else existing.attributes.filterKeys { it != ignore }
        return newAttrs == existingAttrs
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
        return withinDateTolerance &&
            StringSimilarity.similarity(transfer.description, existing.description) >= policy.similarityThreshold
    }

    // Dedupe runs on resolved CREATE transfers, whose fields are present; null indicates a builder error.
    private fun AccountRef?.requireId(): AccountId =
        when (this) {
            is AccountRef.Existing -> {
                id
            }

            is AccountRef.Local -> {
                error("ImportDeduper requires resolved account references; got unresolved $key")
            }

            null -> {
                error("ImportDeduper requires a resolved account reference; got null")
            }
        }
}
