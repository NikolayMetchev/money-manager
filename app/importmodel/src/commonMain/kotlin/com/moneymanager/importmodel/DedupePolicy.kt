package com.moneymanager.importmodel

import com.moneymanager.domain.model.AttributeTypeId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

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
     */
    data object UniqueIdentifier : DedupePolicy

    /**
     * Match against existing transfers by all fields: first an exact core-field + attribute match,
     * then a fuzzy match (same amount, a shared account, posting date within [dateTolerance], and a
     * description similarity at or above [similarityThreshold]). Existing-only — does not dedupe
     * within the same batch. Used by CSV/QIF strategies without unique-identifier columns.
     *
     * The default [similarityThreshold] is [DESCRIPTION_SIMILARITY_THRESHOLD].
     */
    data class FuzzyAllFields(
        val dateTolerance: Duration = 3.days,
        val similarityThreshold: Double = DESCRIPTION_SIMILARITY_THRESHOLD,
    ) : DedupePolicy

    /**
     * API multi-key dedupe: an incoming transfer is a DUPLICATE if it matches an existing transfer by
     * (a) API transaction id ([ImportTransfer.apiId]), OR (b) [ImportTransfer.uniqueKey], OR (c) a
     * field match (same timestamp + amount + the same pair of accounts in either direction). Otherwise
     * it is IMPORTED. Also dedupes within the same batch. Never produces UPDATED — API imports skip
     * matches rather than updating them. Requires [ImportBatch.apiIdExtractor]/[ImportBatch.uniqueKeyExtractor]
     * to derive the same keys from existing transfers.
     *
     * Cross-source reconciliation: when [reconcileWindow] and [reconciledExclusionAttributeTypeId] are
     * set, an incoming transfer that does NOT match by id/key/exact-fields but does match an existing
     * transfer from a *different* source (same source+target+amount, timestamp within [reconcileWindow])
     * is still IMPORTED — but tagged with the exclusion attribute (value "reconciled:<otherId>") so it is
     * kept and linked yet excluded from balance totals, leaving the movement counted once. Restricted to
     * existing transfers this provider cannot itself identify (apiId == null) so genuine repeat transfers
     * from the same provider are never collapsed.
     */
    data class ApiMultiKey(
        val reconcileWindow: Duration? = null,
        val reconciledExclusionAttributeTypeId: AttributeTypeId? = null,
    ) : DedupePolicy

    /** No deduplication — every transfer is imported. */
    data object None : DedupePolicy
}
