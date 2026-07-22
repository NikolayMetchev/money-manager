package com.moneymanager.importengineapi

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.RelationshipTypeId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Links this strategy's [exchangeAccountId] to an [appAccountId] the user also owns, where a transfer
 * between the two is recorded once at each end (e.g. the Crypto.com App CSV records a withdrawal out of
 * "Crypto.com" while the Exchange API records a deposit in). See [DedupePolicy.ApiMultiKey].
 */
data class AccountBridge(
    val exchangeAccountId: AccountId,
    val appAccountId: AccountId,
)

/**
 * Descriptions at or above this normalised similarity are treated as the same transaction. Single
 * source of truth, shared by [DedupePolicy.FuzzyAllFields] and `StringSimilarity` (in the importer
 * module, which depends on this one).
 */
const val DESCRIPTION_SIMILARITY_THRESHOLD = 0.85

/** How the central import engine decides whether an incoming transfer already exists. */
sealed interface DedupePolicy {
    /**
     * Match by [ImportTransfer.uniqueKey]: identical existing transfer -> DUPLICATE, an existing
     * transfer with the same key but differing fields -> UPDATED. Also dedupes within the same batch
     * (a later transfer with a key already seen in this batch is treated as a duplicate). Used by the
     * API importer and by CSV strategies that declare unique-identifier columns.
     *
     * Cross-source reconciliation: when [reconcileWindow], [reconciledExclusionAttributeTypeId] and
     * [reconciledRelationshipTypeId] are all set, an incoming transfer whose unique key does not match
     * any existing transfer, but which does match an existing transfer on the same source+target+amount
     * with a timestamp within [reconcileWindow], is still IMPORTED — tagged with the exclusion
     * attribute and linked via a `reconciled` relationship, so a movement two sources both record under
     * different ids (e.g. a Monzo transfer's own- and joint-account exports, each with their own
     * `Transaction ID`) is counted once. Defaults are null: reconciliation is opt-in per strategy and
     * off for all pre-existing behavior.
     */
    data class UniqueIdentifier(
        val reconcileWindow: Duration? = null,
        val reconciledExclusionAttributeTypeId: AttributeTypeId? = null,
        val reconciledRelationshipTypeId: RelationshipTypeId? = null,
    ) : DedupePolicy

    /**
     * Match against existing transfers by all fields: first an exact core-field + attribute match,
     * then a fuzzy match (same amount, a shared account, posting date within [dateTolerance], and a
     * description similarity at or above [similarityThreshold]). Existing-only — does not dedupe
     * within the same batch. Used by CSV/QIF strategies without unique-identifier columns.
     *
     * The default [similarityThreshold] is [DESCRIPTION_SIMILARITY_THRESHOLD].
     *
     * Cross-source reconciliation: when [reconcileWindow], [reconciledExclusionAttributeTypeId] and
     * [reconciledRelationshipTypeId] are all set, an incoming transfer that neither exactly nor
     * fuzzily matches but does match an existing transfer on the same source+target+amount with a
     * timestamp within [reconcileWindow] is still IMPORTED — tagged with the exclusion attribute and
     * linked via a `reconciled` relationship, so a movement two exports both record (e.g. a
     * crypto.com card top-up present in both the card and fiat CSVs) is counted once. Defaults are
     * null: reconciliation is opt-in per strategy and off for all pre-existing behavior.
     */
    data class FuzzyAllFields(
        val dateTolerance: Duration = 3.days,
        val similarityThreshold: Double = DESCRIPTION_SIMILARITY_THRESHOLD,
        val reconcileWindow: Duration? = null,
        val reconciledExclusionAttributeTypeId: AttributeTypeId? = null,
        val reconciledRelationshipTypeId: RelationshipTypeId? = null,
    ) : DedupePolicy

    /**
     * API multi-key dedupe: an incoming transfer is a DUPLICATE if it matches an existing transfer by
     * (a) API transaction id ([ImportTransfer.apiId]), OR (b) [ImportTransfer.uniqueKey], OR (c) a
     * field match (same timestamp + amount + the same pair of accounts in either direction). Otherwise
     * it is IMPORTED. Also dedupes within the same batch. Never produces UPDATED — API imports skip
     * matches rather than updating them. Requires [ImportBatch.apiIdExtractor]/[ImportBatch.uniqueKeyExtractor]
     * to derive the same keys from existing transfers.
     *
     * Cross-source reconciliation: when [reconcileWindow], [reconciledExclusionAttributeTypeId] and
     * [reconciledRelationshipTypeId] are set, an incoming transfer that does NOT match by
     * id/key/exact-fields but does match an existing transfer from a *different* source
     * (same source+target+amount, timestamp within [reconcileWindow]) is still IMPORTED — but tagged
     * with the plain exclusion attribute (so it is kept yet excluded from balance totals) and linked to
     * the existing transfer via a `reconciled` [com.moneymanager.domain.model.TransferRelationship],
     * leaving the movement counted once. Restricted to existing transfers this provider cannot itself
     * identify (apiId == null) so genuine repeat transfers from the same provider are never collapsed.
     */
    data class ApiMultiKey(
        val reconcileWindow: Duration? = null,
        val reconciledExclusionAttributeTypeId: AttributeTypeId? = null,
        val reconciledRelationshipTypeId: RelationshipTypeId? = null,
        /**
         * Internal-transfer reconciliation between owned accounts. When [internalTransferBridges] is
         * non-empty and the exclusion/relationship type ids + [internalTransferWindow] are set, an
         * incoming transfer into/out of a bridge's exchange account that matches (same asset, amount
         * within [internalTransferAmountTolerance], timestamp within the window, opposite direction)
         * an existing leg on the bridge's app account is REWRITTEN into one internal transfer between the
         * two owned accounts; the stale existing app-side leg is marked excluded and the two are linked
         * via the `reconciled` relationship. See the Crypto.com App↔Exchange case.
         */
        val internalTransferBridges: List<AccountBridge> = emptyList(),
        val internalTransferWindow: Duration? = null,
        /** Allowed amount difference as a percentage (BigDecimal for precise monetary comparison). */
        val internalTransferAmountTolerance: BigDecimal = BigDecimal.ZERO,
    ) : DedupePolicy

    /** No deduplication — every transfer is imported. */
    data object None : DedupePolicy
}
