@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importer

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Money
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
import com.moneymanager.importengineapi.selectNearestUnconsumedLeg
import kotlin.time.Duration
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
    /**
     * An existing transfer to mark excluded-from-balances as a side effect of importing [transfer]
     * (internal-transfer reconciliation): the stale app-side leg whose movement is now represented by
     * the rewritten [transfer]. The engine adds the exclusion attribute to it during the update phase.
     */
    val excludeExisting: ExcludeExistingLeg? = null,
)

/** An existing transfer to tag excluded (its [transfer] carries the real id + fields to preserve). */
data class ExcludeExistingLeg(
    val transfer: Transfer,
    val exclusionTypeId: AttributeTypeId,
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

    /** Directed (source, target, amount) key for exact-account reconcile/candidate lookups. */
    private data class DirectedAmountKey(
        val sourceAccountId: AccountId,
        val targetAccountId: AccountId,
        val amount: Money?,
    )

    private fun Transfer.coreKey() = CoreKey(timestamp, sourceAccountId, targetAccountId, amount, description)

    private fun ImportTransfer.coreKey() = CoreKey(timestamp, fromAccount.requireId(), toAccount.requireId(), amount, description)

    // Indexes over the existing transfers so classification is a hash lookup (plus a scan of the small
    // matching bucket) instead of a linear pass over the whole DB history per incoming row. Buckets
    // preserve input order, so first-match-in-list-order semantics are unchanged.
    private val existingByCoreKey: Map<CoreKey, ExistingTransferInfo> =
        buildMap {
            for (info in existing) {
                val key = info.transfer.coreKey()
                if (key !in this) put(key, info)
            }
        }
    private val existingByAmount: Map<Money, List<ExistingTransferInfo>> =
        existing.groupBy { it.transfer.amount }

    // The reconciliation exclusion attribute type (when the policy enables cross-source reconciliation),
    // ignored in attribute comparison so a reconciled transfer still dedupes cleanly on re-import.
    private val reconciledExclusionTypeId: AttributeTypeId? =
        when (policy) {
            is DedupePolicy.FuzzyAllFields -> policy.reconciledExclusionAttributeTypeId
            is DedupePolicy.ApiMultiKey -> policy.reconciledExclusionAttributeTypeId
            else -> null
        }

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

    // Reconciliation only considers existing transfers this provider cannot identify by its own id
    // (apiId == null) — i.e. transfers from a different source — so genuine repeats from the same
    // provider (which carry an apiId) are never reconciled away.
    private val reconcileCandidates: List<Pair<TransferId, Transfer>> =
        existing.filter { it.apiId == null }.map { it.transferId to it.transfer }

    // reconcileMatches needs exact source+target+amount (only the timestamp window varies), so bucket
    // the reconcile candidates by that triple.
    private val reconcileCandidatesByDirectedAmount: Map<DirectedAmountKey, List<Pair<TransferId, Transfer>>> =
        reconcileCandidates.groupBy { (_, t) -> DirectedAmountKey(t.sourceAccountId, t.targetAccountId, t.amount) }

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

    // Existing legs already claimed by an earlier funding-card or internal-transfer reconcile in this
    // batch. Unlike plain cross-source reconcile (which deliberately doesn't consume), both of these rules
    // must claim each existing leg at most once: a conduit like Curve emits many rows of the same amount
    // (e.g. daily £1.75 TFL), and an exchange bridge sees repeated round-amount withdrawals that could
    // otherwise all link to a single existing bank credit, fabricating phantom duplicates on that side.
    // Scope is a single import() call: this does NOT know which legs a previous, separate batch already
    // consumed, so a later batch's row could re-link to an already-reconciled leg if the amount+window
    // coincide. In practice a conduit/exchange export is imported in one batch, so the collision needs two
    // overlapping imports; CsvReimport.computeFundingReconcileReruns mirrors this same single-batch
    // limitation for the funding-card path.
    private val consumedReconcileIds = mutableSetOf<TransferId>()

    fun classify(transfers: List<ImportTransfer>): List<Classified> =
        transfers.mapIndexed { index, transfer -> classifyOne(index, transfer) }

    private fun classifyOne(
        index: Int,
        transfer: ImportTransfer,
    ): Classified =
        when (policy) {
            is DedupePolicy.None -> Classified(transfer, ImportStatus.IMPORTED, null)
            is DedupePolicy.UniqueIdentifier -> classifyByUniqueId(transfer, policy)
            is DedupePolicy.FuzzyAllFields -> classifyByAllFields(transfer, policy)
            is DedupePolicy.ApiMultiKey -> classifyByApiMultiKey(index, transfer, policy)
        }

    private fun classifyByApiMultiKey(
        index: Int,
        transfer: ImportTransfer,
        policy: DedupePolicy.ApiMultiKey,
    ): Classified {
        // Match an existing DB transfer first (yields its real id) ...
        val existingId =
            transfer.apiId?.let { existingApiId[it] }
                ?: transfer.uniqueKey?.takeIf { it.isNotEmpty() }?.let { existingUniqueKeyId[it] }
                ?: apiMatchCandidatesFor(transfer)
                    .firstOrNull { (id, e) -> apiMatches(transfer, e) && !apiIdConflict(transfer.apiId, existingApiIdByTransferId[id]) }
                    ?.first
        if (existingId != null) return Classified(transfer, ImportStatus.DUPLICATE, existingId)

        // Cross-source reconciliation: the same real movement seen from another provider. Keep this
        // record but tag it excluded-and-linked so the movement is counted once (see ApiMultiKey docs).
        classifyAsReconciled(
            transfer,
            policy.reconcileWindow,
            policy.reconciledExclusionAttributeTypeId,
            policy.reconciledRelationshipTypeId,
        )?.let { return it }

        // Internal-transfer reconciliation between two owned accounts (e.g. Crypto.com App -> Exchange):
        // rewrite the incoming leg into one internal transfer and exclude the stale app-side leg.
        classifyAsInternalTransferReconciled(transfer, policy)?.let { return it }

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

    /**
     * Classifies [transfer] as a cross-source reconciliation of an existing transfer when the policy
     * enables it and a same source+target+amount transfer from a different source exists within the
     * reconcile window. The returned transfer carries a plain exclusion attribute (value "reconciled")
     * so it is kept but excluded from balance totals, plus a `reconciled` relationship linking it
     * (id1) to the existing transfer (id2). Null when reconciliation is disabled or no match is found.
     *
     * Known imperfection: matches are not consumed, so two identical incoming rows within the window
     * both link to the same existing transfer. Balances stay correct (both are excluded).
     */
    private fun classifyAsReconciled(
        transfer: ImportTransfer,
        window: Duration?,
        exclusionTypeId: AttributeTypeId?,
        relationshipTypeId: RelationshipTypeId?,
    ): Classified? {
        if (window == null || exclusionTypeId == null || relationshipTypeId == null) return null
        val key = DirectedAmountKey(transfer.fromAccount.requireId(), transfer.toAccount.requireId(), transfer.amount)
        val matchId =
            reconcileCandidatesByDirectedAmount[key]
                ?.firstOrNull { (_, existing) -> reconcileMatches(transfer, existing, window) }
                ?.first
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

    /**
     * Reconciles a conduit spend (e.g. a Curve export row, `conduit -> merchant`) against the funding
     * leg that put the money into the conduit (`fundingAccount -> conduit`), when the row named its
     * funding card and it resolved to [ImportTransfer.reconcileFundingAccountId]. Matches on
     * amount+currency (via [Money] equality) and timestamp within the window, IGNORING the merchant —
     * so it links across the merchant-naming differences that defeat [classifyAsReconciled]. Each
     * funding leg is consumed at most once (repeated same-amount rows), preferring the nearest
     * timestamp. The row is kept IMPORTED but excluded and linked to the funding leg, so the spend is
     * counted once (the funding leg's own pass-through spend leg remains the merchant record).
     */
    private fun classifyAsFundingReconciled(
        transfer: ImportTransfer,
        window: Duration?,
        exclusionTypeId: AttributeTypeId?,
        relationshipTypeId: RelationshipTypeId?,
    ): Classified? {
        if (window == null || exclusionTypeId == null || relationshipTypeId == null) return null
        val fundingAccountId = transfer.reconcileFundingAccountId ?: return null
        val timestamp = transfer.timestamp ?: return null
        // Funding leg is fundingAccount -> conduit; the incoming row's source IS the conduit.
        val key = DirectedAmountKey(fundingAccountId, transfer.fromAccount.requireId(), transfer.amount)
        val matchId =
            reconcileCandidatesByDirectedAmount[key]
                ?.let { selectNearestUnconsumedLeg(it, timestamp, window, consumedReconcileIds) }
                ?: return null
        consumedReconcileIds += matchId
        val attributes =
            if (transfer.attributes.any { it.typeId == exclusionTypeId }) {
                transfer.attributes
            } else {
                transfer.attributes + NewAttribute(exclusionTypeId, "reconciled")
            }
        val relationships = transfer.relationships + NewRelationship(relatedTransferId = matchId, typeId = relationshipTypeId)
        return Classified(
            transfer.copy(attributes = attributes, relationships = relationships, fee = null),
            ImportStatus.IMPORTED,
            existing = null,
        )
    }

    /**
     * Reconciles an internal transfer between two owned accounts recorded once at each end. The incoming
     * leg moves into/out of a bridge's exchange account against a dangling external counterparty; a
     * matching existing leg (same asset, amount within tolerance, timestamp within window, opposite
     * direction) touches the bridge's app account. On a match the incoming leg is rewritten to run
     * directly between the two owned accounts (so balances net correctly in one movement), linked to the
     * existing leg via the `reconciled` relationship, and the existing app-side leg is flagged excluded.
     *
     * Each existing leg is claimed at most once (nearest-timestamp preferred), via the shared
     * [consumedReconcileIds] set: repeated same-amount exchange movements (e.g. round-number withdrawals)
     * would otherwise all link to the same single existing bank credit, fabricating phantom duplicates on
     * the bank side.
     */
    private fun classifyAsInternalTransferReconciled(
        transfer: ImportTransfer,
        policy: DedupePolicy.ApiMultiKey,
    ): Classified? {
        val window = policy.internalTransferWindow ?: return null
        val exclusionTypeId = policy.reconciledExclusionAttributeTypeId ?: return null
        val relationshipTypeId = policy.reconciledRelationshipTypeId ?: return null
        if (policy.internalTransferBridges.isEmpty()) return null
        val amount = transfer.amount ?: return null
        val timestamp = transfer.timestamp ?: return null
        val from = transfer.fromAccount.requireId()
        val to = transfer.toAccount.requireId()

        return policy.internalTransferBridges.firstNotNullOfOrNull { bridge ->
            val exchangeIsTarget = to == bridge.exchangeAccountId // a deposit into the exchange
            val exchangeIsSource = from == bridge.exchangeAccountId // a withdrawal out of the exchange
            if (!exchangeIsTarget && !exchangeIsSource) {
                null
            } else {
                val candidates =
                    reconcileCandidates.filter { (_, existing) ->
                        existing.amount.asset.id == amount.asset.id &&
                            amountWithinTolerance(amount, existing.amount, policy.internalTransferAmountTolerance) &&
                            (
                                (exchangeIsTarget && existing.sourceAccountId == bridge.appAccountId) ||
                                    (exchangeIsSource && existing.targetAccountId == bridge.appAccountId)
                            )
                    }
                selectNearestUnconsumedLeg(candidates, timestamp, window, consumedReconcileIds)?.let { matchId ->
                    consumedReconcileIds += matchId
                    val existingTransfer = candidates.first { it.first == matchId }.second
                    val rewritten =
                        if (exchangeIsTarget) {
                            transfer.copy(fromAccount = AccountRef.Existing(bridge.appAccountId))
                        } else {
                            transfer.copy(toAccount = AccountRef.Existing(bridge.appAccountId))
                        }
                    val relationships =
                        rewritten.relationships + NewRelationship(relatedTransferId = matchId, typeId = relationshipTypeId)
                    Classified(
                        transfer = rewritten.copy(relationships = relationships, fee = null),
                        status = ImportStatus.IMPORTED,
                        existing = null,
                        excludeExisting = ExcludeExistingLeg(existingTransfer, exclusionTypeId),
                    )
                }
            }
        }
    }

    private fun amountWithinTolerance(
        incoming: Money,
        existing: Money,
        tolerancePercent: BigDecimal,
    ): Boolean {
        if (incoming.amount == existing.amount) return true
        if (tolerancePercent <= BigDecimal.ZERO) return false
        // diff * 100 <= |existing| * tolerancePercent, in exact decimal math (no fractional-percent loss).
        val diff = (incoming.amount - existing.amount).abs().toBigDecimal()
        return diff * BigDecimal(100) <= existing.amount.abs().toBigDecimal() * tolerancePercent
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

    private fun classifyByUniqueId(
        transfer: ImportTransfer,
        policy: DedupePolicy.UniqueIdentifier,
    ): Classified {
        val key = transfer.uniqueKey
        if (!key.isNullOrEmpty()) {
            val existing = existingByUniqueKey[key]
            if (existing != null) {
                val status =
                    if (transfersAreIdentical(transfer, existing)) ImportStatus.DUPLICATE else ImportStatus.UPDATED
                return Classified(transfer, status, existing.transferId)
            }
        }

        // Cross-source reconciliation: the same real movement seen from another provider/export under a
        // different unique id (e.g. Monzo issues a separate Transaction ID per account side of a transfer).
        classifyAsReconciled(
            transfer,
            policy.reconcileWindow,
            policy.reconciledExclusionAttributeTypeId,
            policy.reconciledRelationshipTypeId,
        )?.let { return it }

        if (key.isNullOrEmpty()) return Classified(transfer, ImportStatus.IMPORTED, null)

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
        existingByCoreKey[transfer.coreKey()]?.let { existing ->
            val status =
                if (attributesAreIdentical(transfer, existing)) ImportStatus.DUPLICATE else ImportStatus.UPDATED
            return Classified(transfer, status, existing.transferId)
        }
        // Funding-card reconcile (before fuzzy): a conduit spend whose funding card resolved to an
        // account reconciles against that account's funding leg by amount+currency+window, ignoring the
        // merchant — so it links (excluded) instead of dropping as a fuzzy duplicate or double-counting.
        classifyAsFundingReconciled(
            transfer,
            policy.reconcileWindow,
            policy.reconciledExclusionAttributeTypeId,
            policy.reconciledRelationshipTypeId,
        )?.let { return it }
        // Second pass: tolerate bank re-export drift (close date, similar description) — the amount
        // must still match exactly, so only that bucket needs scanning.
        transfer.amount?.let { amount ->
            existingByAmount[amount]?.forEach { existing ->
                if (isFuzzyDuplicate(transfer, existing.transfer, policy)) {
                    return Classified(transfer, ImportStatus.DUPLICATE, existing.transferId)
                }
            }
        }
        // Third pass: cross-source reconciliation (opt-in per strategy) — the same real movement
        // recorded by another export with a different description (e.g. a crypto.com top-up seen as
        // "Top Up Card" in the fiat CSV and "GBP Deposit" in the card CSV). Imported but excluded+linked.
        classifyAsReconciled(
            transfer,
            policy.reconcileWindow,
            policy.reconciledExclusionAttributeTypeId,
            policy.reconciledRelationshipTypeId,
        )?.let { return it }
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
