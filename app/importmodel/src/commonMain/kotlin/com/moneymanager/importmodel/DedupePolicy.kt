package com.moneymanager.importmodel

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

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
     * The default [similarityThreshold] mirrors `StringSimilarity.DESCRIPTION_SIMILARITY_THRESHOLD`
     * (kept in sync intentionally; that constant lives in the engine module which this module can't see).
     */
    data class FuzzyAllFields(
        val dateTolerance: Duration = 3.days,
        val similarityThreshold: Double = DEFAULT_SIMILARITY_THRESHOLD,
    ) : DedupePolicy {
        companion object {
            const val DEFAULT_SIMILARITY_THRESHOLD = 0.85
        }
    }

    /**
     * API multi-key dedupe: an incoming transfer is a DUPLICATE if it matches an existing transfer by
     * (a) API transaction id ([ImportTransfer.apiId]), OR (b) [ImportTransfer.uniqueKey], OR (c) a
     * field match (same timestamp + amount + the same pair of accounts in either direction). Otherwise
     * it is IMPORTED. Also dedupes within the same batch. Never produces UPDATED — API imports skip
     * matches rather than updating them. Requires [ImportBatch.apiIdExtractor]/[ImportBatch.uniqueKeyExtractor]
     * to derive the same keys from existing transfers.
     */
    data object ApiMultiKey : DedupePolicy

    /** No deduplication — every transfer is imported. */
    data object None : DedupePolicy
}
