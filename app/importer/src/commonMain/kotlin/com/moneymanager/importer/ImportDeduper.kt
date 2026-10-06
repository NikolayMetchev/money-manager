package com.moneymanager.importer

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.bigdecimal.BigInteger
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
    /** See [ImportTransfer.approximateUntil]: set when this leg's timestamp is only the earliest bound. */
    val approximateUntil: Instant? = null,
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
    /**
     * Existing pass-through legs a conduit's own statement makes redundant: the chain's inferred account
     * of what the conduit did with money its statement records itself (see [ConduitStatementMatch]). The
     * engine tags each excluded during the update phase, like [excludeExisting].
     */
    val supersededLegs: List<ExcludeExistingLeg> = emptyList(),
    /** Set when this pass-through row's chain runs through a conduit whose own statement is already imported. */
    val conduitStatement: ConduitStatementMatch? = null,
)

/**
 * A pass-through chain whose conduit [conduitIndex] already has its own statement imported: an existing
 * placeholder leg ([statementLegId], e.g. a PayPal export's "General Credit Card Deposit", which cannot
 * name the card) records the same money arriving in (or, for a refund, leaving) the conduit as the chain's
 * movement into it — the main transfer for conduit 0, spend leg `conduitIndex - 1` otherwise. The engine
 * links that movement to [statementLegId] (`reconciled`), and creates every spend leg from the conduit
 * onward excluded: those legs are the card statement's guess at what the conduit did with the money, and
 * the conduit's statement records what it actually did (the real payee, a currency conversion first, …).
 */
data class ConduitStatementMatch(
    val conduitIndex: Int,
    val statementLegId: TransferId,
    val relationshipTypeId: RelationshipTypeId,
    val exclusionTypeId: AttributeTypeId,
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
    /**
     * Existing legs another record has already reconciled against (they are the `id2` of a `reconciled`
     * relationship). Consumption within one batch is tracked in memory; this is the same claim made
     * durable, so a later import — or a re-import of the same file — cannot excuse a second row against
     * one leg and erase a genuine movement.
     */
    private val claimedReconcileTargets: Set<TransferId> = emptySet(),
    /**
     * Accounts this import books legs against itself. A leg whose counterparty is one of them is a
     * movement this export lists in its own right (a crypto.com wallet statement records both a bank
     * withdrawal and a card top-up), never a second source's record of the row being classified — so the
     * counterparty-agnostic rule must not pair the two.
     */
    private val ownBatchAccounts: Set<AccountId> = emptySet(),
    /**
     * For each existing pass-through leg, the legs that follow it along its chain towards the merchant (via
     * `pass-through` relationships), in order. When a conduit's own statement reconciles against the
     * chain's movement into the conduit, these onward legs are what the statement supersedes.
     */
    private val passThroughOnward: Map<TransferId, List<Transfer>> = emptyMap(),
    /**
     * Existing transfers the batch's own file created earlier. Never another source's record of a
     * movement — so no cross-source rule may pair a row with one, even when a re-import re-runs only a few
     * of the file's rows and [ownBatchAccounts] no longer covers the rest of the file.
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

    /** Directed (source, target, amount) key for exact-account reconcile/candidate lookups. */
    private data class DirectedAmountKey(
        val sourceAccountId: AccountId,
        val targetAccountId: AccountId,
        val amount: Money?,
    )

    /**
     * One account, the direction money moves through it, and the amount — the whole of what an
     * unidentified-counterparty row can assert about a movement.
     */
    private data class AccountFlowKey(
        val accountId: AccountId,
        val inflow: Boolean,
        val amount: Money?,
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
    private val existingById: Map<TransferId, ExistingTransferInfo> = existing.associateBy { it.transferId }

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
        existing.filter { it.apiId == null && it.transferId !in ownSourceLegs }.map { it.transferId to it.transfer }

    /**
     * The merchant-side end of every existing pass-through chain (`conduit -> merchant`, or the merchant's
     * refund back into the conduit). Its counterparty is the merchant, so it is never the movement a
     * placeholder on the conduit — the conduit's record of a card funding it or taking a refund back —
     * stands for.
     */
    private val chainMerchantLegs: Set<TransferId> =
        passThroughOnward.values.mapNotNullTo(mutableSetOf()) { it.lastOrNull()?.id }

    // reconcileMatches needs exact source+target+amount (only the timestamp window varies), so bucket
    // the reconcile candidates by that triple.
    private val reconcileCandidatesByDirectedAmount: Map<DirectedAmountKey, List<Pair<TransferId, Transfer>>> =
        reconcileCandidates.groupBy { (_, t) -> DirectedAmountKey(t.sourceAccountId, t.targetAccountId, t.amount) }

    // The unidentified-counterparty rule matches on the owned account + direction + amount only (the
    // counterparty being the one field such a row cannot state), so index both legs of every candidate
    // that way.
    private val reconcileCandidatesByAccountFlow: Map<AccountFlowKey, List<Pair<TransferId, Transfer>>> =
        buildMap<AccountFlowKey, MutableList<Pair<TransferId, Transfer>>> {
            for (candidate in reconcileCandidates) {
                val (_, existingTransfer) = candidate
                getOrPut(AccountFlowKey(existingTransfer.targetAccountId, inflow = true, existingTransfer.amount)) {
                    mutableListOf()
                } += candidate
                getOrPut(AccountFlowKey(existingTransfer.sourceAccountId, inflow = false, existingTransfer.amount)) {
                    mutableListOf()
                } += candidate
            }
        }

    // Existing legs whose timestamp is only the earliest bound (see ExistingTransferInfo.approximateUntil).
    private val approximateUntilById: Map<TransferId, Instant> =
        existing.mapNotNull { info -> info.approximateUntil?.let { info.transferId to it } }.toMap()

    private val approximateCandidates: List<Pair<TransferId, Transfer>> =
        reconcileCandidates.filter { (id, _) -> id in approximateUntilById }

    // The net-plus-fee rule looks for two legs leaving one account at one instant, whatever their amounts.
    private val reconcileCandidatesByOutflow: Map<AccountId, List<Pair<TransferId, Transfer>>> =
        reconcileCandidates.groupBy { (_, t) -> t.sourceAccountId }

    // The attribute marking a leg whose counterparty is only a description-derived placeholder.
    private val unidentifiedCounterpartyTypeId: AttributeTypeId? =
        when (policy) {
            is DedupePolicy.FuzzyAllFields -> policy.unidentifiedCounterpartyAttributeTypeId
            is DedupePolicy.ApiMultiKey -> policy.unidentifiedCounterpartyAttributeTypeId
            is DedupePolicy.UniqueIdentifier -> policy.unidentifiedCounterpartyAttributeTypeId
            else -> null
        }

    /**
     * Existing legs already excluded from balances. They represent no counted movement, so the
     * unidentified-counterparty rule must not treat one as the real record of a movement — excluding a
     * placeholder against an already-excluded leg would count the movement zero times. (Amount+direction
     * alone is a loose enough match for this to happen: a £100 withdrawal and an excluded £100 card
     * top-up days apart both move £100 out of the same wallet.)
     */
    private val existingExcludedLegs: MutableSet<TransferId> =
        // A unique-id policy keeps the exclusion type out of [reconciledExclusionTypeId] (its attribute
        // comparison is unchanged), but its placeholder and conduit rules need the excluded legs all the same.
        (reconciledExclusionTypeId ?: (policy as? DedupePolicy.UniqueIdentifier)?.reconciledExclusionAttributeTypeId)
            ?.let { typeId -> existing.filter { typeId in it.attributes }.mapTo(mutableSetOf()) { it.transferId } }
            ?: mutableSetOf()

    /** Existing legs some row of this batch was matched or reconciled against; never superseded afterwards. */
    private val pairedInBatch = mutableSetOf<TransferId>()

    /** Existing legs carrying [unidentifiedCounterpartyTypeId]: placeholder records a real one supersedes. */
    private val existingUnidentifiedLegs: Set<TransferId> =
        unidentifiedCounterpartyTypeId
            ?.let { typeId -> existing.filter { typeId in it.attributes }.map { it.transferId }.toSet() }
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

    /**
     * Existing legs an earlier row of this batch already matched (exactly or fuzzily). A persisted leg
     * records ONE movement, so it can be the twin of at most one incoming row: without this, an export
     * that gives every deposit the same description ("GBP Deposit (via FPS)") loses a genuine second
     * £1,000 deposit hours later, because the fuzzy pass — which deliberately tolerates date drift —
     * matches it against the first one's leg as well.
     */
    private val matchedExistingIds = mutableSetOf<TransferId>()

    fun classify(transfers: List<ImportTransfer>): List<Classified> {
        val classified = transfers.mapIndexed { index, transfer -> classifyOne(index, transfer) }.toMutableList()
        reconcileSplitConduitMovements(classified)
        return classified
    }

    /** What the placeholder rules need from the policy, or null when it leaves them off. */
    private class PlaceholderSettings(
        val window: Duration,
        val exclusionTypeId: AttributeTypeId,
        val relationshipTypeId: RelationshipTypeId,
    )

    private val placeholderSettings: PlaceholderSettings? =
        run {
            if (unidentifiedCounterpartyTypeId == null) return@run null
            val (window, exclusion, relationship) =
                when (policy) {
                    is DedupePolicy.FuzzyAllFields ->
                        Triple(policy.dateTolerance, policy.reconciledExclusionAttributeTypeId, policy.reconciledRelationshipTypeId)
                    is DedupePolicy.ApiMultiKey ->
                        Triple(
                            policy.unidentifiedCounterpartyWindow,
                            policy.reconciledExclusionAttributeTypeId,
                            policy.reconciledRelationshipTypeId,
                        )
                    is DedupePolicy.UniqueIdentifier ->
                        Triple(
                            policy.unidentifiedCounterpartyWindow,
                            policy.reconciledExclusionAttributeTypeId,
                            policy.reconciledRelationshipTypeId,
                        )
                    DedupePolicy.None -> return@run null
                }
            if (window == null || exclusion == null || relationship == null) null else PlaceholderSettings(window, exclusion, relationship)
        }

    /**
     * One movement of a pass-through chain into a conduit (or, for a refund, out of it): an existing leg
     * whose onward legs end at [merchant], or a pass-through row of this batch at one of its conduits.
     */
    private class ChainMovement(
        val conduit: AccountId,
        val merchant: AccountId,
        val inflow: Boolean,
        val amount: Money,
        val timestamp: Instant,
        val existing: Transfer? = null,
        val batchIndex: Int? = null,
        val conduitIndex: Int = 0,
    )

    /** A placeholder leg on a conduit: the conduit's own statement, which cannot name the card. */
    private class PlaceholderMovement(
        val conduit: AccountId,
        val inflow: Boolean,
        val amount: Money,
        val timestamp: Instant,
        val existing: Transfer? = null,
        val batchIndex: Int? = null,
    )

    /**
     * A conduit's statement records one movement where the card statement split it into several: Uber
     * authorises £21.58 on the card, tops it up by £0.27 the next day, and PayPal records one £21.85
     * deposit — or charges £107.84 and refunds £0.03 against PayPal's £107.81. The single-amount rules
     * cannot pair those, so the merchant would be paid twice (once per statement).
     *
     * Runs after every row is classified, over what they left unmatched: a placeholder leg on a conduit
     * pairs with two to [MAX_SPLIT_LEGS] chain movements through that conduit to the **same merchant**,
     * within the placeholder window, whose amounts — charges plus, refunds minus — sum to it exactly. The
     * outcome is the single-movement one, applied to every movement of the set: the placeholder is
     * excluded, each chain movement is linked to it and kept, and each chain's legs beyond the conduit are
     * excluded. Either import order works: a placeholder of this batch pairs with existing chain legs, and
     * an existing placeholder pairs with a set holding at least one chain row of this batch.
     */
    private fun reconcileSplitConduitMovements(classified: MutableList<Classified>) {
        val settings = placeholderSettings ?: return
        val chains = existingChainMovements() + batchChainMovements(classified, settings)
        if (chains.isEmpty()) return
        val conduits = chains.mapTo(mutableSetOf()) { it.conduit }
        val used = mutableSetOf<ChainMovement>()
        for (placeholder in batchPlaceholders(classified, settings) + existingPlaceholders(conduits)) {
            val set = splitSetFor(placeholder, chains.filter { it !in used }, settings.window) ?: continue
            used += set
            if (placeholder.batchIndex != null) {
                applyToBatchPlaceholder(classified, placeholder.batchIndex, set, settings)
            } else {
                applyToExistingPlaceholder(classified, requireNotNull(placeholder.existing), set, settings)
            }
        }
    }

    private fun existingChainMovements(): List<ChainMovement> =
        passThroughOnward.mapNotNull { (id, onward) ->
            val leg = existingById[id]?.transfer ?: return@mapNotNull null
            val first = onward.firstOrNull() ?: return@mapNotNull null
            val inflow =
                when {
                    first.touches(leg.targetAccountId) -> true
                    first.touches(leg.sourceAccountId) -> false
                    else -> return@mapNotNull null
                }
            val unusable =
                id in ownSourceLegs ||
                    id in existingExcludedLegs ||
                    id in claimedReconcileTargets ||
                    id in consumedReconcileIds ||
                    id in pairedInBatch ||
                    first.id in existingExcludedLegs
            if (unusable) return@mapNotNull null
            ChainMovement(
                conduit = if (inflow) leg.targetAccountId else leg.sourceAccountId,
                merchant = if (inflow) onward.last().targetAccountId else onward.last().sourceAccountId,
                inflow = inflow,
                amount = leg.amount,
                timestamp = leg.timestamp,
                existing = leg,
            )
        }

    private fun batchChainMovements(
        classified: List<Classified>,
        settings: PlaceholderSettings,
    ): List<ChainMovement> =
        classified.withIndex().flatMap { (index, c) ->
            val passThrough = c.transfer.passThrough
            val timestamp = c.transfer.timestamp
            if (passThrough == null || timestamp == null || !c.isUnmatchedImport(settings)) return@flatMap emptyList()
            passThrough.conduits.mapIndexed { conduitIndex, conduit ->
                ChainMovement(
                    conduit = conduit.requireId(),
                    merchant = passThrough.merchantTarget.requireId(),
                    inflow = !passThrough.incoming,
                    amount = passThrough.amount,
                    timestamp = timestamp,
                    batchIndex = index,
                    conduitIndex = conduitIndex,
                )
            }
        }

    private fun batchPlaceholders(
        classified: List<Classified>,
        settings: PlaceholderSettings,
    ): List<PlaceholderMovement> =
        classified.withIndex().mapNotNull { (index, c) ->
            val t = c.transfer
            val placeholder = t.unidentifiedCounterpartyAccountId ?: return@mapNotNull null
            if (!c.isUnmatchedImport(settings)) return@mapNotNull null
            val from = t.fromAccount.requireId()
            PlaceholderMovement(
                conduit = if (placeholder == from) t.toAccount.requireId() else from,
                inflow = placeholder == from,
                amount = t.amount ?: return@mapNotNull null,
                timestamp = t.timestamp ?: return@mapNotNull null,
                batchIndex = index,
            )
        }

    private fun existingPlaceholders(conduits: Set<AccountId>): List<PlaceholderMovement> =
        existingUnidentifiedLegs.mapNotNull { id ->
            if (id in ownSourceLegs ||
                id in existingExcludedLegs ||
                id in claimedReconcileTargets ||
                id in consumedReconcileIds ||
                id in pairedInBatch
            ) {
                return@mapNotNull null
            }
            val leg = existingById[id]?.transfer ?: return@mapNotNull null
            val inflow =
                when {
                    leg.targetAccountId in conduits -> true
                    leg.sourceAccountId in conduits -> false
                    else -> return@mapNotNull null
                }
            PlaceholderMovement(
                conduit = if (inflow) leg.targetAccountId else leg.sourceAccountId,
                inflow = inflow,
                amount = leg.amount,
                timestamp = leg.timestamp,
                existing = leg,
            )
        }

    /** An IMPORTED row no rule paired with anything, and that is not itself excluded. */
    private fun Classified.isUnmatchedImport(settings: PlaceholderSettings): Boolean =
        status == ImportStatus.IMPORTED &&
            excludeExisting == null &&
            conduitStatement == null &&
            supersededLegs.isEmpty() &&
            !transfer.isExcluded(settings.exclusionTypeId) &&
            transfer.relationships.none { it.typeId == settings.relationshipTypeId }

    /**
     * The chain movements of one merchant, near [placeholder], that sum to it — the closest-in-time such
     * set over all merchants. A placeholder already imported needs at least one movement of this batch:
     * otherwise this import has nothing to change.
     */
    private fun splitSetFor(
        placeholder: PlaceholderMovement,
        chains: List<ChainMovement>,
        window: Duration,
    ): List<ChainMovement>? {
        val target = placeholder.amount.amount
        return chains
            .filter { chain ->
                chain.conduit == placeholder.conduit &&
                    chain.amount.asset == placeholder.amount.asset &&
                    (chain.timestamp - placeholder.timestamp).absoluteValue <= window &&
                    (placeholder.batchIndex == null || chain.existing != null)
            }.groupBy { it.merchant }
            .values
            .mapNotNull { group ->
                val nearest = group.sortedBy { (it.timestamp - placeholder.timestamp).absoluteValue }.take(MAX_SPLIT_CANDIDATES)
                subsetsOf(nearest)
                    .filter { set -> placeholder.batchIndex != null || set.any { it.batchIndex != null } }
                    .firstOrNull { set ->
                        set.fold(BigInteger.ZERO) { sum, chain ->
                            if (chain.inflow == placeholder.inflow) sum + chain.amount.amount else sum - chain.amount.amount
                        } == target
                    }
            }.minByOrNull { set -> set.maxOf { (it.timestamp - placeholder.timestamp).absoluteValue } }
    }

    /** Every subset of [items] with 2 to [MAX_SPLIT_LEGS] members, smallest first. */
    private fun <T> subsetsOf(items: List<T>): List<List<T>> =
        (1 until (1 shl items.size))
            .map { mask -> items.filterIndexed { i, _ -> mask and (1 shl i) != 0 } }
            .filter { it.size in 2..MAX_SPLIT_LEGS }
            .sortedBy { it.size }

    /** The conduit's statement row is in this batch: it is excluded and linked to every chain movement. */
    private fun applyToBatchPlaceholder(
        classified: MutableList<Classified>,
        index: Int,
        set: List<ChainMovement>,
        settings: PlaceholderSettings,
    ) {
        val legs = set.map { requireNotNull(it.existing) }
        legs.forEach {
            consumedReconcileIds += it.id
            pairedInBatch += it.id
        }
        val superseded = set.flatMap { onwardLegsFrom(requireNotNull(it.existing).id, it.conduit) }
        supersede(superseded)
        val transfer = classified[index].transfer
        classified[index] =
            classified[index].copy(
                transfer =
                    transfer.copy(
                        attributes = transfer.withExclusion(settings.exclusionTypeId),
                        relationships =
                            transfer.relationships +
                                (legs + superseded).map { NewRelationship(it.id, settings.relationshipTypeId) },
                        fee = null,
                    ),
                supersededLegs = superseded.map { ExcludeExistingLeg(it, settings.exclusionTypeId) },
            )
    }

    /**
     * The conduit's statement row was imported earlier: it is excluded (carried by the first chain row of
     * this batch in the set), each chain row of this batch is linked to it with its onward legs created
     * excluded, and the onward legs of any existing chain leg in the set are excluded too.
     */
    private fun applyToExistingPlaceholder(
        classified: MutableList<Classified>,
        placeholder: Transfer,
        set: List<ChainMovement>,
        settings: PlaceholderSettings,
    ) {
        consumedReconcileIds += placeholder.id
        pairedInBatch += placeholder.id
        val existingLegs = set.mapNotNull { it.existing }
        existingLegs.forEach { pairedInBatch += it.id }
        val superseded = set.filter { it.existing != null }.flatMap { onwardLegsFrom(requireNotNull(it.existing).id, it.conduit) }
        supersede(superseded)
        val batchMovements = set.filter { it.batchIndex != null }
        batchMovements.forEachIndexed { i, movement ->
            val index = requireNotNull(movement.batchIndex)
            classified[index] =
                classified[index].copy(
                    excludeExisting = if (i == 0) ExcludeExistingLeg(placeholder, settings.exclusionTypeId) else null,
                    supersededLegs = if (i == 0) superseded.map { ExcludeExistingLeg(it, settings.exclusionTypeId) } else emptyList(),
                    conduitStatement =
                        ConduitStatementMatch(movement.conduitIndex, placeholder.id, settings.relationshipTypeId, settings.exclusionTypeId),
                )
        }
    }

    private fun Transfer.touches(account: AccountId): Boolean = sourceAccountId == account || targetAccountId == account

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

        // Cross-source reconciliation: the same real movement seen from another provider, counted once.
        // An approximately-dated record yields to a precise one (either way round), and between equals the
        // existing record stays (see ApiMultiKey docs).
        for (rule in listOf(approximateTwin, sameAccounts, preciseTwin)) {
            reconcile(
                transfer,
                rule,
                policy.reconcileWindow,
                policy.reconciledExclusionAttributeTypeId,
                policy.reconciledRelationshipTypeId,
            )?.let { return it }
        }

        // Internal-transfer reconciliation between two owned accounts (e.g. Crypto.com App -> Exchange):
        // rewrite the incoming leg into one internal transfer and exclude the stale app-side leg.
        classifyAsInternalTransferReconciled(transfer, policy)?.let { return it }

        classifyAsConduitStatement(
            transfer,
            policy.unidentifiedCounterpartyWindow,
            policy.reconciledExclusionAttributeTypeId,
            policy.reconciledRelationshipTypeId,
        )?.let { return it }

        // A record naming the far end (a bank resolving it by sort code + account number) beats a
        // placeholder (an exchange's "fiat deposit"), either way round; a feed itemising a charge the other
        // source folded into one gross row beats that gross row.
        for (rule in listOf(placeholderTwin, identifiedTwin, grossTwin)) {
            reconcile(
                transfer,
                rule,
                policy.unidentifiedCounterpartyWindow,
                policy.reconciledExclusionAttributeTypeId,
                policy.reconciledRelationshipTypeId,
            )?.let { return it }
        }

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

    /** Which of two records of one movement stays counted; the other is imported or kept, but excluded. */
    private enum class Keeps { EXISTING, INCOMING }

    /**
     * What a [ReconcileRule] found: the existing leg recording the same movement as the incoming one, and
     * every existing leg the match claims (each may be the twin of at most one incoming row; see
     * [consumedReconcileIds]).
     */
    private data class Found(
        val id: TransferId,
        val existing: Transfer,
        val claims: List<TransferId>,
        val supersedes: List<Transfer> = emptyList(),
    )

    /**
     * One way an incoming leg and an existing record can describe the same movement — the one leg matcher
     * behind every cross-source reconcile. Rules differ only in how they find the existing record (same
     * accounts; the owned account and direction when one side's counterparty is a placeholder; overlapping
     * time spans when one side is approximately dated; a gross amount against a net leg plus its fee) and
     * in [keeps], which one ranking fixes per rule: a precisely-timed record beats an approximately-timed
     * one, a record naming its counterparty beats a placeholder, an itemised (net + fee) record beats a
     * gross one, and between equals the existing record — already counted — stays.
     *
     * A rule never claims anything itself: [reconcile] records [Found.claims] only once a rule matched, so
     * a rule that declines leaves the batch's claims untouched.
     */
    private class ReconcileRule(
        val keeps: Keeps,
        val find: (transfer: ImportTransfer, window: Duration) -> Found?,
    )

    /**
     * Applies [rule] to [transfer]: null when the policy leaves reconciliation off or the rule finds no
     * twin; otherwise the incoming leg, linked to its twin via `reconciled`, with whichever record [rule]
     * ranks lower excluded. The excluded record's fee goes with it: a duplicate's fee is itself a
     * duplicate, and re-creating it would double-count.
     */
    private fun reconcile(
        transfer: ImportTransfer,
        rule: ReconcileRule,
        window: Duration?,
        exclusionTypeId: AttributeTypeId?,
        relationshipTypeId: RelationshipTypeId?,
    ): Classified? {
        if (window == null || exclusionTypeId == null || relationshipTypeId == null) return null
        // An excluded incoming leg counts nowhere, so it must never supersede — and exclude — a counted
        // one: that would leave the movement counted zero times.
        if (rule.keeps == Keeps.INCOMING && transfer.isExcluded(exclusionTypeId)) return null
        val found = rule.find(transfer, window) ?: return null
        consumedReconcileIds += found.claims
        pairedInBatch += found.id
        supersede(found.supersedes)
        // Superseded legs are linked too, so the exclusion they take on stays explained — and is lifted by
        // `reconciledPartnerUnhideUpdates` if this row is ever deleted.
        val relationships =
            transfer.relationships + NewRelationship(relatedTransferId = found.id, typeId = relationshipTypeId) +
                found.supersedes.map { NewRelationship(relatedTransferId = it.id, typeId = relationshipTypeId) }
        return when (rule.keeps) {
            Keeps.EXISTING ->
                Classified(
                    transfer.copy(attributes = transfer.withExclusion(exclusionTypeId), relationships = relationships, fee = null),
                    ImportStatus.IMPORTED,
                    existing = null,
                    supersededLegs = found.supersedes.map { ExcludeExistingLeg(it, exclusionTypeId) },
                )
            Keeps.INCOMING ->
                Classified(
                    transfer.copy(relationships = relationships),
                    ImportStatus.IMPORTED,
                    existing = null,
                    excludeExisting = ExcludeExistingLeg(found.existing, exclusionTypeId),
                )
        }
    }

    /** The unclaimed leg of [candidates] nearest [timestamp] within [window], claimed by the match. */
    private fun nearestUnclaimed(
        candidates: List<Pair<TransferId, Transfer>>,
        timestamp: Instant,
        window: Duration,
    ): Found? {
        val id = selectNearestUnconsumedLeg(candidates, timestamp, window, consumedReconcileIds) ?: return null
        return Found(id, candidates.first { it.first == id }.second, claims = listOf(id))
    }

    /**
     * The same movement between the same two accounts, from a different source, within the window. Matches
     * are not claimed, so two identical incoming rows within the window both link to the same existing
     * transfer — balances stay correct, as both are excluded.
     *
     * An existing leg that is itself already excluded is never matched: it represents no counted movement,
     * so excluding this row against it would count the movement zero times (a third export of the same
     * movement would otherwise chain-exclude the whole set).
     */
    private val sameAccounts =
        ReconcileRule(Keeps.EXISTING) { transfer, window ->
            val key = DirectedAmountKey(transfer.fromAccount.requireId(), transfer.toAccount.requireId(), transfer.amount)
            reconcileCandidatesByDirectedAmount[key]
                ?.firstOrNull { (id, existing) -> id !in existingExcludedLegs && reconcileMatches(transfer, existing, window) }
                ?.let { (id, existing) -> Found(id, existing, claims = emptyList()) }
        }

    /**
     * A conduit spend (e.g. a Curve export row, `conduit -> merchant`) against the funding leg that put the
     * money into the conduit (`fundingAccount -> conduit`), when the row named its funding card and it
     * resolved to [ImportTransfer.reconcileFundingAccountId]. Matches on amount+currency and time, ignoring
     * the merchant — so it links across the merchant-naming differences that defeat [sameAccounts]. The
     * funding leg's own pass-through spend leg remains the merchant record.
     */
    private val fundingLeg =
        ReconcileRule(Keeps.EXISTING) { transfer, window ->
            val fundingAccountId = transfer.reconcileFundingAccountId ?: return@ReconcileRule null
            val timestamp = transfer.timestamp ?: return@ReconcileRule null
            // Funding leg is fundingAccount -> conduit; the incoming row's source IS the conduit.
            val key = DirectedAmountKey(fundingAccountId, transfer.fromAccount.requireId(), transfer.amount)
            reconcileCandidatesByDirectedAmount[key]?.let { nearestUnclaimed(it, timestamp, window) }
        }

    /**
     * This leg's counterparty is only a placeholder named after the row's own description
     * ([ImportTransfer.unidentifiedCounterpartyAccountId]); an existing, identified leg moves the same amount
     * the same way through the *owned* account — the counterparty, precisely what this row could not
     * resolve, is ignored. That is what lets a bank export (which names the far account by sort code +
     * account number) and an account's own export (which says only "GBP Deposit (via FPS)") record one
     * deposit once. Two placeholder legs are left to [sameAccounts] (their accounts are equal).
     */
    private val identifiedTwin =
        ReconcileRule(Keeps.EXISTING) { transfer, window ->
            if (unidentifiedCounterpartyTypeId == null) return@ReconcileRule null
            val placeholder = transfer.unidentifiedCounterpartyAccountId ?: return@ReconcileRule null
            val timestamp = transfer.timestamp ?: return@ReconcileRule null
            val from = transfer.fromAccount.requireId()
            val to = transfer.toAccount.requireId()
            // The owned account is whichever side the placeholder is not; money flows into it when the
            // placeholder funds the row, out of it when the placeholder receives.
            val (owned, inflow) =
                when (placeholder) {
                    from -> to to true
                    to -> from to false
                    else -> return@ReconcileRule null
                }
            val candidates =
                reconcileCandidatesByAccountFlow[AccountFlowKey(owned, inflow, transfer.amount)]
                    ?.filter { (id, existingTransfer) ->
                        val counterparty = if (inflow) existingTransfer.sourceAccountId else existingTransfer.targetAccountId
                        id !in existingUnidentifiedLegs &&
                            id !in existingExcludedLegs &&
                            id !in claimedReconcileTargets &&
                            id !in chainMerchantLegs &&
                            counterparty != placeholder &&
                            counterparty !in ownBatchAccounts
                    }.orEmpty()
            nearestUnclaimed(candidates, timestamp, window)?.let { it.copy(supersedes = onwardLegsFrom(it.id, owned)) }
        }

    /**
     * The pass-through legs that follow [legId] along its chain, when the chain continues from [conduit] —
     * i.e. [legId] moved money into or out of the conduit whose own statement is being imported. The
     * statement records what the conduit really did with that money, so these inferred legs are redundant.
     * Legs already excluded, or that another row of this batch already paired with, are left alone.
     */
    private fun onwardLegsFrom(
        legId: TransferId,
        conduit: AccountId,
    ): List<Transfer> {
        val onward = passThroughOnward[legId].orEmpty()
        val first = onward.firstOrNull() ?: return emptyList()
        if (first.sourceAccountId != conduit && first.targetAccountId != conduit) return emptyList()
        return onward.filter { it.id !in existingExcludedLegs && it.id !in pairedInBatch && it.id !in matchedExistingIds }
    }

    /**
     * Marks legs this batch supersedes (see [Classified.supersededLegs]) as excluded, so no later rule treats
     * one as a counted record and the fuzzy pass never drops a row as its duplicate.
     */
    private fun supersede(legs: List<Transfer>) {
        legs.forEach { existingExcludedLegs += it.id }
    }

    /**
     * This pass-through row's chain runs through a conduit whose own statement is already imported: an
     * existing placeholder leg moves the chain's amount the same way through that conduit (into it for a
     * charge, out of it for a refund) — see [ConduitStatementMatch]. The chain's movement into the conduit
     * is the better record of that money (it names the card), so the placeholder is excluded; the chain's
     * legs beyond the conduit are the worse record of what happened next, so they are created excluded.
     * The outermost conduit with a match wins.
     */
    private fun classifyAsConduitStatement(
        transfer: ImportTransfer,
        window: Duration?,
        exclusionTypeId: AttributeTypeId?,
        relationshipTypeId: RelationshipTypeId?,
    ): Classified? {
        val passThrough = transfer.passThrough ?: return null
        if (window == null || exclusionTypeId == null || relationshipTypeId == null || unidentifiedCounterpartyTypeId == null) return null
        if (transfer.isExcluded(exclusionTypeId)) return null
        val timestamp = transfer.timestamp ?: return null
        val inflow = !passThrough.incoming
        return passThrough.conduits.withIndex().firstNotNullOfOrNull { (conduitIndex, conduit) ->
            val candidates =
                reconcileCandidatesByAccountFlow[AccountFlowKey(conduit.requireId(), inflow, passThrough.amount)]
                    ?.filter { (id, _) ->
                        id in existingUnidentifiedLegs && id !in existingExcludedLegs && id !in claimedReconcileTargets
                    }.orEmpty()
            nearestUnclaimed(candidates, timestamp, window)?.let { found ->
                consumedReconcileIds += found.claims
                pairedInBatch += found.id
                Classified(
                    transfer,
                    ImportStatus.IMPORTED,
                    existing = null,
                    excludeExisting = ExcludeExistingLeg(found.existing, exclusionTypeId),
                    conduitStatement = ConduitStatementMatch(conduitIndex, found.id, relationshipTypeId, exclusionTypeId),
                )
            }
        }
    }

    /**
     * The mirror of [identifiedTwin] for the opposite import order: this row names both ends of the
     * movement, and an existing leg recorded the same movement against a description-derived placeholder.
     */
    private val placeholderTwin =
        ReconcileRule(Keeps.INCOMING) { transfer, window ->
            if (unidentifiedCounterpartyTypeId == null) return@ReconcileRule null
            if (transfer.unidentifiedCounterpartyAccountId != null) return@ReconcileRule null
            val timestamp = transfer.timestamp ?: return@ReconcileRule null
            val from = transfer.fromAccount.requireId()
            val to = transfer.toAccount.requireId()
            // This row moves money out of `from` and into `to`; a placeholder leg for the same movement sits
            // on one of those accounts, flowing the same way, with some other account as its far end.
            listOf(Triple(to, true, from), Triple(from, false, to)).firstNotNullOfOrNull { (owned, inflow, counterparty) ->
                val candidates =
                    reconcileCandidatesByAccountFlow[AccountFlowKey(owned, inflow, transfer.amount)]
                        ?.filter { (id, existingTransfer) ->
                            id in existingUnidentifiedLegs &&
                                id !in existingExcludedLegs &&
                                id !in claimedReconcileTargets &&
                                (if (inflow) existingTransfer.sourceAccountId else existingTransfer.targetAccountId) != counterparty
                        }.orEmpty()
                nearestUnclaimed(candidates, timestamp, window)
            }
        }

    /**
     * Another source could only date this movement approximately ([ExistingTransferInfo.approximateUntil]):
     * an existing leg moving the same amount the same way through the same accounts — or, when this row's
     * counterparty is only a placeholder, through the same owned account — at an instant inside its span.
     * This record is kept, so the movement is counted once and at its real time (which is what lets a third
     * source, like a tax tool's export, line up with it).
     */
    private val approximateTwin =
        ReconcileRule(Keeps.INCOMING) { transfer, window ->
            if (approximateCandidates.isEmpty() || transfer.approximateUntil != null) return@ReconcileRule null
            val timestamp = transfer.timestamp ?: return@ReconcileRule null
            val from = transfer.fromAccount.requireId()
            val to = transfer.toAccount.requireId()
            val placeholder = transfer.unidentifiedCounterpartyAccountId
            approximateCandidates
                .filter { (id, existing) ->
                    val sameAccounts =
                        existing.sourceAccountId == from &&
                            existing.targetAccountId == to ||
                            placeholder == from &&
                            existing.targetAccountId == to ||
                            placeholder == to &&
                            existing.sourceAccountId == from
                    existing.amount == transfer.amount &&
                        sameAccounts &&
                        id !in existingExcludedLegs &&
                        id !in claimedReconcileTargets &&
                        id !in consumedReconcileIds &&
                        spansMeet(timestamp, null, existing.timestamp, approximateUntilById[id], window)
                }.minByOrNull { (_, existing) -> (timestamp - existing.timestamp).absoluteValue }
                ?.let { (id, existing) -> Found(id, existing, claims = listOf(id)) }
        }

    /**
     * The mirror of [approximateTwin]: this leg is the approximately-dated one, and an existing leg moving
     * the same amount the same way through one of this leg's accounts, inside its span, is the precise
     * record. Counterparties are not compared — the precise source may only have a placeholder for the far
     * end, or a different name for it.
     */
    private val preciseTwin =
        ReconcileRule(Keeps.EXISTING) { transfer, window ->
            val exclusionTypeId = reconciledExclusionTypeId
            if (exclusionTypeId != null && transfer.isExcluded(exclusionTypeId)) return@ReconcileRule null
            val until = transfer.approximateUntil ?: return@ReconcileRule null
            val timestamp = transfer.timestamp ?: return@ReconcileRule null
            listOf(
                AccountFlowKey(transfer.toAccount.requireId(), inflow = true, transfer.amount),
                AccountFlowKey(transfer.fromAccount.requireId(), inflow = false, transfer.amount),
            ).flatMap { reconcileCandidatesByAccountFlow[it].orEmpty() }
                .filter { (id, existing) ->
                    id !in existingExcludedLegs &&
                        id !in claimedReconcileTargets &&
                        id !in consumedReconcileIds &&
                        id !in approximateUntilById &&
                        spansMeet(timestamp, until, existing.timestamp, null, window)
                }.minByOrNull { (_, existing) -> (existing.timestamp - timestamp).absoluteValue }
                ?.let { (id, existing) -> Found(id, existing, claims = listOf(id)) }
        }

    /**
     * This source reports the movement **net** of a charge it books separately, and another source
     * recorded it as one **gross** leg. A Binance withdrawal is the case: the API returns the amount that
     * left the account and a `transactionFee`/`totalFee` beside it, which become two transfers, while the
     * statement export has one row for the sum. Amount equality — which every other rule rests on — can
     * never pair those, so the match is on [ImportTransfer.reconcileGrossAmount], counterparty-agnostic
     * (the gross row typically named only a placeholder). Net + fee is exactly the gross, so no balance
     * moves.
     */
    private val grossTwin =
        ReconcileRule(Keeps.INCOMING) { transfer, window ->
            val gross = transfer.reconcileGrossAmount ?: return@ReconcileRule null
            if (gross == transfer.amount) return@ReconcileRule null
            val timestamp = transfer.timestamp ?: return@ReconcileRule null
            val from = transfer.fromAccount.requireId()
            val to = transfer.toAccount.requireId()
            listOf(to to true, from to false).firstNotNullOfOrNull { (owned, inflow) ->
                val candidates =
                    reconcileCandidatesByAccountFlow[AccountFlowKey(owned, inflow, gross)]
                        ?.filter { (id, _) -> id !in existingExcludedLegs && id !in claimedReconcileTargets }
                        .orEmpty()
                nearestUnclaimed(candidates, timestamp, window)
            }
        }

    /**
     * The mirror of [grossTwin]: this row is the single **gross** leg and another source already booked
     * the movement as a **net** leg plus a separate fee leg. A Bybit withdrawal is the case: the Funding
     * ledger debits the coins that left plus the network fee in one row, while the API books the amount
     * received and `withdrawFee` as two transfers.
     *
     * Only a placeholder row qualifies (its counterparty is unknown, which is why it cannot be the better
     * record). The match is two identified, unexcluded legs leaving the owned account at one shared instant
     * within the window whose amounts sum exactly to this row's; a coincidence that tight is not plausible
     * for two unrelated movements. This row links to the larger (net) leg; both existing legs are claimed.
     */
    private val itemisedTwin =
        ReconcileRule(Keeps.EXISTING) { transfer, window ->
            if (unidentifiedCounterpartyTypeId == null) return@ReconcileRule null
            val placeholder = transfer.unidentifiedCounterpartyAccountId ?: return@ReconcileRule null
            val gross = transfer.amount ?: return@ReconcileRule null
            val timestamp = transfer.timestamp ?: return@ReconcileRule null
            // Only an outflow from the owned account: money leaves gross, arrives net, and the fee goes elsewhere.
            if (placeholder != transfer.toAccount.requireId()) return@ReconcileRule null
            val owned = transfer.fromAccount.requireId()
            val usable =
                reconcileCandidatesByOutflow[owned]
                    .orEmpty()
                    .filter { (id, existing) ->
                        existing.amount.asset == gross.asset &&
                            existing.amount.amount < gross.amount &&
                            existing.targetAccountId != placeholder &&
                            id !in existingUnidentifiedLegs &&
                            id !in existingExcludedLegs &&
                            id !in claimedReconcileTargets &&
                            id !in consumedReconcileIds &&
                            (timestamp - existing.timestamp).absoluteValue <= window
                    }
            usable
                .groupBy { (_, existing) -> existing.timestamp }
                .values
                .flatMap { legs ->
                    legs.flatMapIndexed { i, net ->
                        legs.drop(i + 1).mapNotNull { fee ->
                            if (net.second.amount + fee.second.amount != gross) return@mapNotNull null
                            if (net.second.amount.amount >= fee.second.amount.amount) net to fee else fee to net
                        }
                    }
                }.filter { (net, _) -> net.second.targetAccountId !in ownBatchAccounts }
                .minByOrNull { (net, _) -> (timestamp - net.second.timestamp).absoluteValue }
                ?.let { (net, fee) -> Found(net.first, net.second, claims = listOf(net.first, fee.first)) }
        }

    /**
     * True when this incoming leg already arrives excluded (the source marks it deleted, or an internal
     * move between wallets of one account). It counts nowhere, so it must never be the record that
     * supersedes — and excludes — an existing leg: that would leave the movement counted zero times.
     */
    private fun ImportTransfer.isExcluded(exclusionTypeId: AttributeTypeId): Boolean =
        excludedFromBalances || attributes.any { it.typeId == exclusionTypeId }

    /** This transfer's attributes plus the reconciliation exclusion, unless it already carries one. */
    private fun ImportTransfer.withExclusion(exclusionTypeId: AttributeTypeId): List<NewAttribute> =
        if (attributes.any { it.typeId == exclusionTypeId }) {
            attributes
        } else {
            attributes + NewAttribute(exclusionTypeId, "reconciled")
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
        return spansMeet(requireNotNull(transfer.timestamp), transfer.approximateUntil, existing.timestamp, null, window)
    }

    /**
     * True when two movements' possible instants come within [window] of each other. A precise leg's span
     * is its timestamp alone; an approximate one runs from its timestamp to its `until` bound.
     */
    private fun spansMeet(
        start: Instant,
        until: Instant?,
        otherStart: Instant,
        otherUntil: Instant?,
        window: Duration,
    ): Boolean {
        val end = maxOf(start, until ?: start)
        val otherEnd = maxOf(otherStart, otherUntil ?: otherStart)
        return otherStart - window <= end && start - window <= otherEnd
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
            // Not in the database; an earlier transfer in this same batch may have already claimed this
            // key. Must run BEFORE reconciliation: a literal duplicate row that also happens to match a
            // reconcile candidate must still be flagged DUPLICATE, not re-imported as a second "reconciled" copy.
            if (!seenInBatch.add(key)) {
                return Classified(transfer, ImportStatus.DUPLICATE, null)
            }
        }

        // Cross-source reconciliation: the same real movement seen from another provider/export under a
        // different unique id (e.g. Monzo issues a separate Transaction ID per account side of a transfer).
        reconcile(
            transfer,
            sameAccounts,
            policy.reconcileWindow,
            policy.reconciledExclusionAttributeTypeId,
            policy.reconciledRelationshipTypeId,
        )?.let { return it }

        classifyAsConduitStatement(
            transfer,
            policy.unidentifiedCounterpartyWindow,
            policy.reconciledExclusionAttributeTypeId,
            policy.reconciledRelationshipTypeId,
        )?.let { return it }

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
        // Before the fuzzy pass, which would drop this row as a duplicate: another source booked the
        // movement with only an approximate time (this row is the better record), or this is a conduit
        // spend whose funding card resolved to an account (it links to that account's funding leg,
        // ignoring the merchant, instead of dropping as a fuzzy duplicate or double-counting).
        for (rule in listOf(approximateTwin, fundingLeg)) {
            reconcile(
                transfer,
                rule,
                policy.reconcileWindow,
                policy.reconciledExclusionAttributeTypeId,
                policy.reconciledRelationshipTypeId,
            )?.let { return it }
        }
        // Second pass: tolerate bank re-export drift (close date, similar description) — the amount
        // must still match exactly, so only that bucket needs scanning. Of several equal candidates the
        // nearest in time is this row's record: taking the first in id order can pair a statement row with
        // one chain while its same-second funding row reconciles another, counting the movement nowhere.
        transfer.amount?.let { amount ->
            val timestamp = requireNotNull(transfer.timestamp)
            existingByAmount[amount]
                ?.filter { existing ->
                    // An excluded leg counts nowhere, so a row dropped as its duplicate would count nowhere either —
                    // unless the leg is this same file's earlier record of the row, which keeps its exclusion.
                    existing.transferId !in matchedExistingIds &&
                        (existing.transferId !in existingExcludedLegs || existing.transferId in ownSourceLegs) &&
                        isFuzzyDuplicate(transfer, existing.transfer, policy)
                }?.minByOrNull { (it.transfer.timestamp - timestamp).absoluteValue }
                ?.let { existing ->
                    matchedExistingIds += existing.transferId
                    return Classified(transfer, ImportStatus.DUPLICATE, existing.transferId)
                }
        }
        // Then cross-source reconciliation (opt-in per strategy): the same real movement recorded by
        // another export with a different description (a crypto.com top-up seen as "Top Up Card" in the
        // fiat CSV and "GBP Deposit" in the card CSV); a row whose counterparty is only a placeholder,
        // matched on the owned account + direction + amount alone (either way round, within the date
        // tolerance); and a placeholder row that is a gross amount another source split into net + fee.
        reconcile(
            transfer,
            sameAccounts,
            policy.reconcileWindow,
            policy.reconciledExclusionAttributeTypeId,
            policy.reconciledRelationshipTypeId,
        )?.let { return it }
        // Matched within the date tolerance, like the placeholder rules below it is one of.
        classifyAsConduitStatement(
            transfer,
            policy.dateTolerance,
            policy.reconciledExclusionAttributeTypeId,
            policy.reconciledRelationshipTypeId,
        )?.let { return it }
        val rules =
            listOf(
                identifiedTwin to policy.dateTolerance,
                placeholderTwin to policy.dateTolerance,
                itemisedTwin to policy.reconcileWindow,
            )
        for ((rule, window) in rules) {
            reconcile(
                transfer,
                rule,
                window,
                policy.reconciledExclusionAttributeTypeId,
                policy.reconciledRelationshipTypeId,
            )?.let { return it }
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
            is AccountRef.Existing -> id
            is AccountRef.Local ->
                error("ImportDeduper requires resolved account references; got unresolved $key")
            null -> error("ImportDeduper requires a resolved account reference; got null")
        }
}

/** Most chain movements a conduit's single statement row can be split across (an authorisation plus adjustments). */
private const val MAX_SPLIT_LEGS = 4

/** Chain movements per merchant considered for one split, nearest first; bounds the subset search at 2^8. */
private const val MAX_SPLIT_CANDIDATES = 8
